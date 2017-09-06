/**
 * Remove leading whitespace from a multi-line String (probably a shellscript).
 */
@NonCPS
def String dedent(String text) {
  if (text == null) {
    return null
  }
  text.replaceFirst("\n","").stripIndent()
}

/**
 * Thin wrapper around {@code sh} step that strips leading whitspace and
 * enables ANSI color codes.
 */
def void shColor(script) {
  ansiColor('gnome-terminal') {
    sh dedent(script)
  }
}

/**
 * Create a thin "wrapper" container around {@code imageName} to map uid/gid of
 * the user invoking docker into the container.
 *
 * @param imageName docker image slug
 * @param tag name of tag to apply to generated image
 */
def void wrapContainer(String imageName, String tag) {
  def buildDir = 'docker'
  def config = dedent("""
    FROM    ${imageName}

    ARG     USER
    ARG     UID
    ARG     GROUP
    ARG     GID
    ARG     HOME

    USER    root
    RUN     groupadd -g \$GID \$GROUP
    RUN     useradd -d \$HOME -g \$GROUP -u \$UID \$USER

    USER    \$USER
    WORKDIR \$HOME
  """)

  // docker insists on recusrively checking file access under its execution
  // path -- so run it from a dedicated dir
  dir(buildDir) {
    writeFile(file: 'Dockerfile', text: config)

    shColor """
      set -e
      set -x

      docker build -t "${tag}" \
          --build-arg USER="\$(id -un)" \
          --build-arg UID="\$(id -u)" \
          --build-arg GROUP="\$(id -gn)" \
          --build-arg GID="\$(id -g)" \
          --build-arg HOME="\$HOME" \
          .
    """

    deleteDir()
  }
}

/**
 * Join multiple String args togther with '/'s to resemble a filesystem path.
 */
// The groovy String#join method is not working under the security sandbox
// https://issues.jenkins-ci.org/browse/JENKINS-43484
@NonCPS
def String joinPath(String ... parts) {
  String text = null

  def n = parts.size()
  parts.eachWithIndex { x, i ->
    if (text == null) {
      text = x
    } else {
      text += x
    }

    if (i < (n - 1)) {
      text += '/'
    }
  }

  return text
}

/**
 * Serialize a Map to a JSON string and write it to a file.
 *
 * @param filename output filename
 * @param data Map to serialize
 */
@NonCPS
def dumpJson(String filename, Map data) {
  def json = new groovy.json.JsonBuilder(data)
  def pretty = groovy.json.JsonOutput.prettyPrint(json.toString())
  echo pretty
  writeFile file: filename, text: pretty
}

/**
 * Serialize a Map to a JSON string and write it to a file.
 *
 * @param filename output filename
 * @param data Map to serialize
 * @return LazyMap parsed JSON object
 */
@NonCPS
def slurpJson(String data) {
  def slurper = new groovy.json.JsonSlurper()
  slurper.parseText(data)
}


/**
 * Create an EUPS distrib tag
 *
 * @param buildId bNNNN
 * @param eupsTag tag name
 * @param product whitespace delimited string of products to tag
 * @param publishJob job to trigger (does the actual work)
 */
def tagProduct(String buildId, String eupsTag, String product,
               String publishJob = 'release/run-publish') {
  stage("eups publish [${eupsTag}]") {
    build job: publishJob,
      parameters: [
        string(name: 'EUPSPKG_SOURCE', value: 'git'),
        string(name: 'BUILD_ID', value: buildId),
        string(name: 'TAG', value: eupsTag),
        string(name: 'PRODUCT', value: product)
      ]
  }
}

/**
 * Run a lsstsw build.
 *
 * @param label Node label to run on
 * @param python Python major revsion to build with. Eg., 'py2' or 'py3'
 */
def lsstswBuild(String label, String python) {
  node(label) {
    // use different workspace dirs for python 2/3 to avoid residual state
    // conflicts
    def slug = "${label}.${python}"

    try {
      dir(slug) {
        try {
          dir('lsstsw') {
            git([
              url: 'https://github.com/lsst/lsstsw.git',
              branch: 'master',
              changelog: false,
              poll: false
            ])
          }

          dir('buildbot-scripts') {
            git([
              url: 'https://github.com/lsst-sqre/buildbot-scripts.git',
              branch: 'master',
              changelog: false,
              poll: false
            ])
          }

          withCredentials([[
            $class: 'StringBinding',
            credentialsId: 'cmirror-s3-bucket',
            variable: 'CMIRROR_S3_BUCKET'
          ]]) {
            withEnv([
              "WORKSPACE=${pwd()}",
              'SKIP_DOCS=true',
              "python=${python}",
              "LSST_JUNIT_PREFIX=${slug}"
            ]) {
              util.shColor './buildbot-scripts/jenkins_wrapper.sh'
            }
          } // withCredentials([[
        } finally {
          withEnv(["WORKSPACE=${pwd()}"]) {
            util.shColor '''
              if hash lsof 2>/dev/null; then
                Z=$(lsof -d 200 -t)
                if [[ ! -z $Z ]]; then
                  kill -9 $Z
                fi
              else
                echo "lsof is missing; unable to kill rebuild related processes."
              fi

              rm -rf "${WORKSPACE}/lsstsw/stack/.lockDir"
            '''
          }
        } // try
      } // dir(slug)
    } finally {
      def lsstsw = "${slug}/lsstsw"
      def lsstsw_build_dir = "${lsstsw}/build"
      def manifestPath = "${lsstsw_build_dir}/manifest.txt"
      def statusPath = "${lsstsw_build_dir}/status.yaml"
      def archive = [
        manifestPath,
        statusPath,
      ]

      try {
        if (fileExists(statusPath)) {
          def status = readYaml(file: statusPath)

          def products = status['built']
          // if there is a "failed_at" product, check it for a junit file too
          if (status['failed_at']) {
            products << status['failed_at']
          }

          def reports = []
          products.each { item ->
            def name = item['name']
            def xml = "${lsstsw_build_dir}/${name}/tests/.tests/pytest-${name}.xml"
            if (fileExists(xml)) {
              reports << xml
            }

            archive += "${lsstsw_build_dir}/${name}/*.log"
          }

          if (reports) {
            // note that junit will ignore files with timestamps before the start
            // of the build
            junit([
              testResults: reports.join(', '),
              allowEmptyResults: true,
            ])

            archive += reports
          }
        }
      } catch (e) {
        // As a last resort, find product build dirs with a wildcard.  This might
        // match logs for products that _are not_ part of the current build.
        archive += "${lsstsw_build_dir}/*/*.log"
        throw e
      } finally {
        archiveArtifacts([
          artifacts: archive.join(', '),
          allowEmptyArchive: true,
          fingerprint: true
        ])
      } // try
    } // try
  } // node(label)
}

return this;
