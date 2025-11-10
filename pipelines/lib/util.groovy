import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import groovy.transform.Field
import java.security.MessageDigest

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
 * Thin wrapper around {@code sh} step that strips leading whitspace.
 */
def void posixSh(script) {
  script = dedent(script)
  sh shebangerize(script, '/bin/sh -xe')
}

/**
 * Thin wrapper around {@code sh} step that strips leading whitspace.
 */
def void bash(script) {
  script = dedent(script)
  sh shebangerize(script, '/bin/bash -xe')
}

/**
 * Prepend a shebang to a String that does not already have one.
 *
 * @param script String Text to prepend a shebang to
 * @return shebangerized String
 */
@NonCPS
def String shebangerize(String script, String prog = '/bin/sh -xe') {
  if (!script.startsWith('#!')) {
    script = "#!${prog}\n${script}"
  }

  script
}

/**
 * Hash a String using the SHA1 algorithm.
 *
 * @param path String representing the path to be hashed
 * @return hashed path String
 */
@NonCPS
def String hashpath(String path) {
  def digest = MessageDigest.getInstance('SHA-1')
  digest.update(path.bytes)
  def hashpathstr = digest.digest().encodeHex().toString()

  return hashpathstr
}

/**
 * Build a docker image, constructing the `Dockerfile` from `config`.
 *
 * Example:
 *
 *     util.buildImage(
 *       config: dockerfileText,
 *       tag: 'example/foo:bar',
 *       pull: true,
 *     )
 *
 * @param p Map
 * @param p.config String literal text of Dockerfile (required)
 * @param p.tag String name of tag to apply to generated image (required)
 * @param p.pull Boolean always pull docker base image (optional)
 */
def void buildImage(Map p) {
  requireMapKeys(p, [
    'config',
    'tag',
  ])

  String config = p.config
  String tag    = p.tag
  Boolean pull  = p.pull ?: false

  def opt = []
  opt << "--pull=${pull}"
  opt << '--build-arg D_USER="$(id -un)"'
  opt << '--build-arg D_UID="$(id -u)"'
  opt << '--build-arg D_GROUP="$(id -gn)"'
  opt << '--build-arg D_GID="$(id -g)"'
  opt << '--build-arg D_HOME="$HOME"'
  opt << '--load'
  opt << '.'

  writeFile(file: 'Dockerfile', text: config)
  docker.build(tag, opt.join(' '))
} // buildImage

/**
 * Create a thin "wrapper" container around {@code image} to map uid/gid of
 * the user invoking docker into the container.
 *
 * Example:
 *
 *     util.wrapDockerImage(
 *       image: 'example/foo:bar',
 *       tag: 'example/foo:bar-local',
 *       pull: true,
 *     )
 *
 * @param p Map
 * @param p.image String name of docker base image (required)
 * @param p.tag String name of tag to apply to generated image
 * @param p.pull Boolean always pull docker base image. Defaults to `false`
 */
def void wrapDockerImage(Map p) {
  requireMapKeys(p, [
    'image',
    'tag',
  ])

  String image = p.image
  String tag   = p.tag
  Boolean pull = p.pull ?: false

  def buildDir = 'docker'
  def config = dedent("""
    FROM ${image}

    ARG     D_USER
    ARG     D_UID
    ARG     D_GROUP
    ARG     D_GID
    ARG     D_HOME

    USER    root
    RUN     mkdir -p "\$(dirname \$D_HOME)"
    RUN     groupadd \$D_GROUP || echo \$D_GROUP already exist
    RUN     useradd -d \$D_HOME -g \$D_GROUP \$D_USER || echo \$D_USER already exist

    USER    \$D_USER
    WORKDIR \$D_HOME
  """)

  // docker insists on recusrively checking file access under its execution
  // path -- so run it from a dedicated dir
  dir(buildDir) {
    buildImage(
      config: config,
      tag: tag,
      pull: pull,
    )

    deleteDir()
  }
} // wrapDockerImage

/**
 * Invoke block inside of a "wrapper" container.  See: wrapDockerImage
 *
 * Example:
 *
 *     util.insideDockerWrap(
 *       image: 'example/foo:bar',
 *       args: '-e HOME=/baz',
 *       pull: true,
 *     )
 *
 * @param p Map
 * @param p.image String name of docker image (required)
 * @param p.args String docker run args (optional)
 * @param p.pull Boolean always pull docker image. Defaults to `false`
 * @param run Closure Invoked inside of wrapper container
 */
def insideDockerWrap(Map p, Closure run) {
  requireMapKeys(p, [
    'image',
  ])

  String image = p.image
  String args  = p.args ?: null
  Boolean pull = p.pull ?: false

  def imageLocal = "${image}-local"

  wrapDockerImage(
    image: image,
    tag: imageLocal,
    pull: pull,
  )

  docker.image(imageLocal).inside(args) { run() }
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
} // joinPath

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
 * Parse a JSON string.
 *
 * @param data String to parse.
 * @return Object parsed JSON object
 */
@NonCPS
def slurpJson(String data) {
  new groovy.json.JsonSlurperClassic().parseText(data)
}

/**
 * Run a command, that is assumed to return JSON, and parse the stdout.
 *
 * @param script String shell script to execute.
 * @return Object parsed JSON object
 */
def shJson(String script) {
  def stdout = sh(returnStdout: true, script: script).trim()
  slurpJson(stdout)
}

/**
 * Create an EUPS distrib tag
 *
 * Example:
 *
 *     util.runPublish(
 *       parameters: [
 *         EUPSPKG_SOURCE: 'git',
 *         MANIFEST_ID: manifestId,
 *         EUPS_TAG: eupsTag,
 *         PRODUCTS: products,
 *       ],
 *     )
 *
 * @param p Map
 * @param p.job String job to trigger. Defaults to `release/run-publish`.
 * @param p.parameters.EUPSPKG_SOURCE String
 * @param p.parameters.MANIFEST_ID String
 * @param p.parameters.EUPS_TAG String
 * @param p.parameters.PRODUCTS String
 * @param p.parameters.TIMEOUT String Defaults to `'1'`.
 * @param p.parameters.SPLENV_REF String Optional
 */
def void runPublish(Map p) {
  requireMapKeys(p, [
    'parameters',
  ])
  def useP = [
    job: 'release/run-publish',
  ] + p

  requireMapKeys(p.parameters, [
    'EUPSPKG_SOURCE',
    'MANIFEST_ID',
    'EUPS_TAG',
    'PRODUCTS',
  ])
  useP.parameters = [
    TIMEOUT: '1' // should be string
  ] + p.parameters

  def jobParameters = [
          string(name: 'EUPSPKG_SOURCE', value: useP.parameters.EUPSPKG_SOURCE),
          string(name: 'MANIFEST_ID', value: useP.parameters.MANIFEST_ID),
          string(name: 'EUPS_TAG', value: useP.parameters.EUPS_TAG),
          string(name: 'PRODUCTS', value: useP.parameters.PRODUCTS),
          string(name: 'TIMEOUT', value: useP.parameters.TIMEOUT.toString()),
  ]

  // Optional parameter. Set 'em if you got 'em
  if (useP.parameters.SPLENV_REF) {
    jobParameters += string(name: 'SPLENV_REF', value: useP.parameters.SPLENV_REF)
  }
  if (useP.parameters.RUBINENV_VER) {
    jobParameters += string(name: 'RUBINENV_VER', value: useP.parameters.RUBINENV_VER)
  }

  build(
    job: useP.job,
    parameters: jobParameters,
  )
} // runPublish

/**
 * Loads LSSTCAM test data
 * @param buildDir where to run this
 * @param testDir where to place the test data
 * @return full path of test data
 */
def loadLSSTCamTestData(
  String buildDir,
  String testDir){
  def gcp_repo = 'ghcr.io/lsst-dm/docker-gcloudcli'
  def testdata // Assigning location of data later
  dir(buildDir) {
  def cwd = pwd()
  testdata = "${cwd}/${testDir}"
  dir(testdata){
    withCredentials([
      [
        $class: 'StringBinding',
        credentialsId: 'weka-bucket-secret',
        variable: 'RCLONE_CONFIG_WEKA_SECRET_ACCESS_KEY'
      ], [
        $class: 'StringBinding',
        credentialsId: 'weka-access-key',
        variable: 'RCLONE_CONFIG_WEKA_ACCESS_KEY_ID'
      ], [
        $class: 'StringBinding',
        credentialsId: 'weka-bucket-url',
        variable: 'RCLONE_CONFIG_WEKA_ENDPOINT'
      ]]){
      withEnv([
        "RCLONE_CONFIG_WEKA_TYPE=s3",
        "RCLONE_CONFIG_WEKA_PROVIDER=Other",
        "LSSTCAM_BUCKET=rubin-ci-lsst/testdata_ci_lsstcam_m49"
    ]){
      insideDockerWrap(
        image: "${gcp_repo}:latest",
        pull: true,
        args: "-v ${cwd}:/home",
      ) {
        bash """
          rclone copy weka:"${LSSTCAM_BUCKET}" .
        """
        }
      }
    }
  }
  }
  return testdata
}
/**
 * Loads Cache
 * @param buildDir where to place the loaded file
 * @param tag Which eups tag to load
 */
def loadCache(
  String buildDir,
  String tag="d_latest"
) {
  def gcp_repo = 'ghcr.io/lsst-dm/docker-gcloudcli'
  dir(buildDir) {
    def cwd = pwd()
    def ciDir = "${cwd}/ci-scripts"
    dir(ciDir){
      cloneCiScripts()
    }
    withCredentials([file(
      credentialsId: 'gs-eups-push',
      variable: 'GOOGLE_APPLICATION_CREDENTIALS'
    )]) {
      withEnv([
        "SERVICEACCOUNT=eups-dev@prompt-proto.iam.gserviceaccount.com",
        "DATE_TAG=${tag}",
      ]) {
          insideDockerWrap(
            image: "${gcp_repo}:latest",
            pull: true,
            args: "-v ${cwd}:/home",
          ) {
             bash """
             gcloud auth activate-service-account $SERVICEACCOUNT --key-file=$GOOGLE_APPLICATION_CREDENTIALS;
             cd /home/ci-scripts
             ./loadlsststack.sh $DATE_TAG
             """
        }
      }
    }
  }
}
/**
 * Save Cache
 * @param buildDir where to place the loaded file
 * @param tag Which eups tag to load
 */
def saveCache(
  String tag="d_latest"
) {
  def cwd = pwd()
  bash '''
    cd lsstsw
    source bin/envconfig
    conda install google-cloud-sdk
  '''
  withCredentials([file(
    credentialsId: 'gs-eups-push',
    variable: 'GOOGLE_APPLICATION_CREDENTIALS'
  )]) {
    withEnv([
      "SERVICEACCOUNT=eups-dev@prompt-proto.iam.gserviceaccount.com",
      "DATE_TAG=${tag}",
    ]) {
        bash """
        cd lsstsw
        source bin/envconfig
        gcloud auth activate-service-account $SERVICEACCOUNT --key-file=$GOOGLE_APPLICATION_CREDENTIALS;
        cd ../ci-scripts
        ./backuplsststack.sh $DATE_TAG
        """
    }
  }
}


/**
 * Run a lsstsw build.
 *
 * @param lsstswConfig Map
 * @param buildParams Map
 * @param wipeout Delete all existing state before starting build
 */
def lsstswBuild(
  Map lsstswConfig,
  Map buildParams,
  Boolean wipeout=false,
  Boolean fetchCache=false,
  Boolean cachelsstsw=false
) {
  validateLsstswConfig(lsstswConfig)
  def slug = lsstswConfigSlug(lsstswConfig)

  buildParams = [
    LSST_COMPILER:       lsstswConfig.compiler,
    LSST_JUNIT_PREFIX:   slug,
    LSST_PYTHON_VERSION: lsstswConfig.python,
    LSST_SPLENV_REF:     lsstswConfig.splenv_ref,
  ] + buildParams


  def run = {
    if (cachelsstsw){ // runs only if we want to cache the work
        buildParams = [SCONSFLAGS: "--no-tests"] + buildParams
        jenkinsWrapper(buildParams)
        saveCache("d_latest")
    } // if saveCacheRun
    else {
        jenkinsWrapper(buildParams)
    } // else
  } // run
  def runDocker = {
    insideDockerWrap(
      image: lsstswConfig.image,
      pull: true,
    ) {
      withCredentials([[
        $class: 'StringBinding',
        credentialsId: 'github-api-token-checks',
        variable: 'GITHUB_TOKEN'
      ]]){
        run()
      } // withCredentials
    } // insideDockerWrap
  } // runDocker

  def runEnv = { doRun ->
      // No longer need hashpath as slug is short enough
      def buildDirHash = slug
      try {
        dir(buildDirHash) {
          if (wipeout) {
            deleteDir()
          }

          try {
            timeout(time: 12, unit: 'HOURS') {
              doRun()
            } // timeout
          } catch (e) {
            if (!lsstswConfig.allow_fail) {
              throw e
            }
            echo "giving up on build but suppressing error"
            echo e.toString()
          } // try
        } // dir
      } finally {
        // needs to be called in the parent dir of jenkinsWrapper() in order to
        // add the slug as a prefix to the archived files.
        jenkinsWrapperPost(buildDirHash)
      }
  } // runEnv

  def agent = lsstswConfig.label
  def task = null
  if (lsstswConfig.image) {
    task = {
      if (fetchCache){
        loadCache(slug,"d_latest")
      }
      if (buildParams['LSSTCAM_ONLY']){
        def testdatadir = loadLSSTCamTestData(slug,"lsstcam_testdata")
        buildParams['LSSTCAM_TESTDATA_DIR'] = testdatadir
      }
      runEnv(runDocker)
    }
  } else {
    if (cachelsstsw){
      // runs only if we are not running a caching job. Since this isn't on
      // docker we do not need to store cache for them.
      return
    }
    else {
      task = { runEnv(run) }
    }
  }

  nodeWrap(agent) {
    task()
  } // nodeWrap
} // lsstswBuild

/**
 * Run a build using ci-scripts/jenkins_wrapper.sh
 *
 * Required keys are listed below. Any additional keys will also be set as env
 * vars.
 * @param buildParams map
 * @param buildParams.LSST_COMPILER String
 * @param buildParams.LSST_PRODUCTS String
 * @param buildParams.LSST_REFS String
 * @param buildParams.LSST_SPLENV_REF String
 */
def void jenkinsWrapper(Map buildParams) {
  // minimum set of required keys -- additional are allowed
  requireMapKeys(buildParams, [
    'LSST_COMPILER',
    'LSST_PRODUCTS',
    'LSST_REFS',
    'LSST_SPLENV_REF',
  ])
  def scipipe = scipipeConfig()

  buildParams = [
    // XXX this should be renamed in lsstsw to make it clear that its setting a
    // github repo slug
    REPOSFILE_REPO: scipipe.repos.github_repo,
  ] + buildParams

  def cwd     = pwd()
  def homeDir = "${cwd}/home"

  try {
    dir('lsstsw') {
      cloneLsstsw()
    }

    dir('ci-scripts') {
      cloneCiScripts()
    }

    // workspace relative dir for dot files to prevent bleed through between
    // jobs and subsequent builds.
    emptyDirs([homeDir])

    // cleanup *all* conda cached package info
    [
      'lsstsw/miniconda/conda-meta',
      'lsstsw/miniconda/pkgs',
    ].each { it ->
      dir(it) {
        deleteDir()
      }
    }

    // This file is needed for conda to know it has a base environment.
    bash '''
      mkdir -p lsstsw/miniconda/conda-meta
      touch lsstsw/miniconda/conda-meta/history
    '''

    // This line uses k8s to set EUPSPKG_NJOBS
    def njobs = 16

    // Check if NODE_LABELS is set in the environment
    def nodeLabels = env.NODE_LABELS

    def buildEnv = [
      "WORKSPACE=${cwd}",
      "HOME=${homeDir}",
      "EUPS_USERDATA=${homeDir}/.eups_userdata",
      "EUPSPKG_NJOBS=${njobs}",
      "NODE_LABELS=${nodeLabels}"
    ]

    // Map -> List
    buildParams.each { pair ->
      buildEnv += pair.toString()
    }

    withEnv(buildEnv) {
      bash './ci-scripts/jenkins_wrapper.sh'
    }
  } finally {
    withEnv(["WORKSPACE=${cwd}"]) {
      bash '''
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
} // jenkinsWrapper

def jenkinsWrapperPost(String baseDir = null, boolean prepOnly = false) {
  def lsstsw = 'lsstsw'

  if (baseDir) {
    lsstsw = "${baseDir}/${lsstsw}"
  }

  // note that archive does not like a leading `./`
  def lsstsw_build_dir = "${lsstsw}/build"
  def manifestPath = "${lsstsw_build_dir}/manifest.txt"
  def statusPath = "${lsstsw_build_dir}/status.yaml"
  def archive = [
    manifestPath,
    statusPath,
  ]

  def archive_exclude = []

  def record = [
    '*.log',
    '*.failed',
  ]

  def failed_record = [
          '_build.log',
          'config.log',
          'tests/.tests/pytest-*.xml',
          '*.failed',
  ]
  def failed_exclude = [
          'tests/.tests/pytest-*.xml-cov-*.xml',
  ]

  try {
    if (!prepOnly) {
      // if only prepare, skip junit
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
          reports << xml

          record.each { pattern ->
            archive += "${lsstsw_build_dir}/${name}/**/${pattern}"
          }
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
      } else {
        // handle case when there is no status.yaml due to timeouts
        // match logs for products that are not part of the current build
        failed_record.each { pattern ->
          archive += "${lsstsw_build_dir}/**/${pattern}"
        }
        failed_exclude.each { pattern ->
          archive_exclude += "${lsstsw_build_dir}/**/${pattern}"
        }
      }
    }
  } catch (e) {
    // As a last resort, find product build dirs with a wildcard.  This might
    // match logs for products that _are not_ part of the current build.
    record.each { pattern ->
      archive += "${lsstsw_build_dir}/**/${pattern}"
    }
    throw e
  } finally {
    archiveArtifacts([
      artifacts: archive.join(', '),
      excludes: archive_exclude.join(', '),
      allowEmptyArchive: true,
      fingerprint: true
    ])
  } // try
} // jenkinsWrapperPost

/**
 * Parse manifest id out of a manifest.txt format String.
 *
 * @param manifest.txt as a String
 * @return manifestId String
 */
@NonCPS
def String parseManifestId(String manifest) {
  def m = manifest =~ /(?m)^BUILD=(b.*)/
  m ? m[0][1] : null
}

/**
 * Validate that required parameters were passed from the job and raise an
 * error on any that are missing.
 *
 * @param rps List of required job parameters
 */
def void requireParams(List rps) {
  rps.each { it ->
    if (params.get(it) == null) {
      error "${it} parameter is required"
    }
  }
}

/**
 * Validate that required env vars were passed from the job and raise an
 * error on any that are missing.
 *
 * @param rev List of required env vars
 */
def void requireEnvVars(List rev) {
  // note that `env` isn't a map and #get doesn't work as expected
  rev.each { it ->
    if (env."${it}" == null) {
      error "${it} environment variable is required"
    }
  }
}

/**
 * Validate that map contains AT LEAST the specified list of keys and raise
 * an error on any that are missing.
 *
 * @param check Map object to inspect
 * @param key List of required map keys
 */
def void requireMapKeys(Map check, List keys) {
  keys.each { k ->
    if (! check.containsKey(k)) {
      error "${k} key is missing from Map"
    }
  }
}

/**
 * Empty directories by deleting and recreating them.
 *
 * @param dirs List of directories to empty
*/
def void emptyDirs(List eds) {
  eds.each { d ->
    dir(d) {
      deleteDir()
      // a file operation is needed to cause the dir() step to recreate the dir
      writeFile(file: '.dummy', text: '')
    }
  }
}

/**
 * Ensure directories exist and create any that are absent.
 *
 * @param dirs List of directories to ensure/create
*/
def void createDirs(List eds) {
  eds.each { d ->
    dir(d) {
      // a file operation is needed to cause the dir() step to recreate the dir
      writeFile(file: '.dummy', text: '')
    }
  }
}

/**
 * XXX this method was developed during the validate_drp conversion to pipeline
 * but is currently unusued.  It has been preserved as it might be useful in
 * other jobs.
 *
 * Write a copy of `manifest.txt`.
 *
 * @param rebuildId String `run-rebuild` build id.
 * @param filename String Output filename.
 */
def void getManifest(String rebuildId, String filename) {
  def manifest_artifact = 'lsstsw/build/manifest.txt'
  def buildJob          = 'release/run-rebuild'

  step([$class: 'CopyArtifact',
        projectName: buildJob,
        filter: manifest_artifact,
        selector: [
          $class: 'SpecificBuildSelector',
          buildNumber: rebuildId // wants a string
        ],
      ])

  def manifest = readFile manifest_artifact
  writeFile(file: filename, text: manifest)
} // getManifest

/**
 * Run the `github-tag-release` script from `sqre-codekit` with parameters.
 *
 * Example:
 *
 *     util.githubTagRelease(
 *       options: [
 *         '--dry-run': true,
 *         '--org': 'myorg'
 *         '--manifest': 'b1234',
 *         '--eups-tag': 'v999_0_0',
 *       ],
 *       args: ['999.0.0'],
 *     )
 *
 * @param p Map
 * @param p.options Map CLI --<options>. Required. See `makeCliCmd`
 * @param p.options.'--org' String Required.
 * @param p.options.'--manifest' String Required.
 * @param p.options.'--eups-tag' String Required.
 * @param p.args List Eg., `[<git tag>]` Required.
 */
def void githubTagRelease(Map p) {
  requireMapKeys(p, [
    'args',
    'options',
  ])
  requireMapKeys(p.options, [
    '--org',
    '--manifest',
  ])

  // compute versiondb url
  def scipipe = scipipeConfig()
  def vdbUrl = "https://raw.githubusercontent.com/${scipipe.versiondb.github_repo}/main/manifests"

  // --eupstag-base-url is needed [when running under a "test" env] if git tags
  // are being generated from an existing eups tag.  If all workflows are
  // changed to git tag from a versiondb manifest prior to the build, it may be
  // removed.
  def eupsUrl = scipipe.eups.base_url
  def etbUrl = "${eupsUrl}/src/tags"

  def prog = 'github-tag-release'
  def defaultOptions = [
    '--debug': true,
    '--dry-run': true,
    '--token': '$GITHUB_TOKEN',
    '--user': 'sqreadmin',
    '--email': 'sqre-admin@lists.lsst.org',
    '--versiondb-base-url': vdbUrl,
    '--eupstag-base-url': etbUrl,
    '--allow-team': ['Data Management', 'DM Externals'],
    '--external-team': 'DM Externals',
    '--deny-team': 'DM Auxilliaries',
    '--fail-fast': true,
  ]

  runCodekitCmd(prog, defaultOptions, p.options, p.args)
} // githubTagRelease

/**
 * Run the `github-tag-teams` script from `sqre-codekit` with parameters.
 *
 * Example:
 *
 *     util.githubTagTeams(
 *       options: [
 *         '--dry-run': true,
 *         '--org': 'myorg',
 *         '--tag': '999.0.0',
 *       ],
 *       args: ['-r', 'v998.0.0.rc1']
 *     )
 *
 * @param p Map
 * @param p.options Map CLI --<options>. Required. See `makeCliCmd`
 * @param p.options.'--org' String Required.
 * @param p.options.'--tag' String|List Required.
 * @param p.args List Eg., `['-r', '<git refs>']` Optional.
 */
def void githubTagTeams(Map p) {
  requireMapKeys(p, [
    'options',
  ])
  requireMapKeys(p.options, [
    '--org',
    '--tag',
  ])
  def prog = 'github-tag-teams'
  def defaultOptions = [
    '--debug': true,
    '--dry-run': true,
    '--token': '$GITHUB_TOKEN',
    '--user': 'sqreadmin',
    '--email': 'sqre-admin@lists.lsst.org',
    '--allow-team': 'DM Auxilliaries',
    '--deny-team': 'DM Externals',
    '--ignore-existing-tag': true,
  ]

  runCodekitCmd(prog, defaultOptions, p.options, p.containsKey('args') ? p.args : null)
} // githubTagTeams

/**
 * Run the `github-get-ratelimit` script from `sqre-codekit`.
 *
 */
def void githubGetRatelimit() {
  def prog = 'github-get-ratelimit'
  def defaultOptions = [
    '--token': '$GITHUB_TOKEN',
  ]

  runCodekitCmd(prog, defaultOptions, null, null)
}

/**
 * Run a codekit cli command.
 *
 * @param prog String see `makeCliCmd`
 * @param defaultOptions Map see `makeCliCmd`
 * @param options Map see `makeCliCmd`
 * @param args List see `makeCliCmd`
 */
def void runCodekitCmd(
  String prog,
  Map defaultOptions,
  Map options,
  List args,
  Integer timelimit = 30
) {
  def cliCmd = makeCliCmd(prog, defaultOptions, options, args)

  timeout(time: timelimit, unit: 'MINUTES') {
    insideCodekit {
      bash cliCmd
    }
  }
} // runCodekitCmd

/**
 * Generate a string for executing a system command with optional flags and/or
 * arguments.
 *
 * @param prog String command to run.
 * @param defaultOptions Map command option flags.
 * @param options Map script option flags.  These are merged with
 * defaultOptions.  Truthy values are considered as an active flag while the
 * literal `true` constant indicates a boolean flag.  Falsey values result in
 * the flag being omitted.  Lists/Arrays result in the flag being specified
 * multiple times.
 * @param args List verbatium arguments to pass to command.
 * @return String complete cli command
 */
def String makeCliCmd(
  String prog,
  Map defaultOptions,
  Map options,
  List args
) {
  def useOpts = [:]

  if (defaultOptions) {
    useOpts = defaultOptions
  }
  if (options) {
    useOpts += options
  }

  cmd = [prog]

  if (useOpts) {
    cmd += mapToCliFlags(useOpts)
  }
  if (args) {
    cmd += listToCliArgs(args)
  }

  return cmd.join(' ')
} // makeCliCmd

/**
 * Run block inside a container with sqre-codekit installed and a github oauth
 * token defined as `GITHUB_TOKEN`.
 *
 * @param run Closure Invoked inside of node step
 */
def void insideCodekit(Closure run) {
  insideDockerWrap(
    image: defaultCodekitImage(),
    pull: true,
  ) {
    withGithubAdminCredentials {
      run()
    }
  } // insideDockerWrap
} // insideCodekit

/**
 * Convert a map of command line flags (keys) and values into a string suitable
 * to be passed on "the cli" to a program
 *
 * @param opt Map script option flags
 */
def String mapToCliFlags(Map opt) {
  def flags = []

  opt.each { k,v ->
    if (v) {
      if (v == true) {
        // its a boolean flag
        flags += k
      } else {
        // its a flag with an arg
        if (v instanceof List) {
          // its a flag with multiple values
          v.each { nested ->
            flags += "${k} \"${nested}\""
          }
        } else {
          // its a flag with a single value
          flags += "${k} \"${v}\""
        }
      }
    }
  }

  return flags.join(' ')
} // mapToCliFlags

/**
 * Convert a List of command line args into a string suitable
 * to be passed on "the cli" to a program
 *
 * @param args List of command arguments
 * @return String of arguments
 */
def String listToCliArgs(List args) {
  return args.collect { "\"${it}\"" }.join(' ')
}

/**
 * Run block with a github oauth token defined as `GITHUB_TOKEN`.
 *
 * @param run Closure Invoked inside of node step
 */
def void withGithubAdminCredentials(Closure run) {
  withCredentials([[
    $class: 'StringBinding',
    credentialsId: 'github-api-token-sqreadmin',
    variable: 'GITHUB_TOKEN'
  ]]) {
    run()
  } // withCredentials
}

/**
 * Run trivial execution time block
 *
 * @param run Closure Invoked inside of node step
 */
def void nodeTiny(Closure run) {
  nodeWrap('jenkins-manager') {
    timeout(time: 5, unit: 'MINUTES') {
      run()
    }
  }
}

/**
 * Execute a multiple multiple lsstsw builds using different configurations.
 *
 * @param matrixConfig List of lsstsw build configurations
 * @param buildParams Map of params/env vars for jenkins_wrapper.sh
 * @param wipeout Boolean wipeout the workspace build starting the build
 */
def lsstswBuildMatrix(
  List matrixConfig,
  Map buildParams,
  Boolean wipeout=false,
  Boolean loadCache=false,
  Boolean saveCache=false
) {
  lsstcam = false
  if (buildParams.containsKey("LSSTCAM_ONLY")){
    lsstcam = buildParams['LSSTCAM_ONLY'].toBoolean()
  }
  if (lsstcam == true){
      def lsstswConfig = matrixConfig[0]
      validateLsstswConfig(lsstswConfig)
      lsstswBuild(
        lsstswConfig,
        buildParams,
        wipeout,
        loadCache,
        saveCache,
      )
  } else {
    def matrix = [:]

    matrixConfig.each { lsstswConfig ->
      validateLsstswConfig(lsstswConfig)
      def slug = lsstswConfigSlug(lsstswConfig)

      matrix[slug] = {
        lsstswBuild(
        lsstswConfig,
        buildParams,
        wipeout,
        loadCache,
        saveCache
        )
      }
    }
    parallel matrix
  } // else
} // lsstswBuildMatrix

/**
 * Clone lsstsw git repo
 */
def void cloneLsstsw() {
  def scipipe = scipipeConfig()

  gitNoNoise(
    url: githubSlugToUrl(scipipe.lsstsw.github_repo),
    branch: scipipe.lsstsw.git_ref,
  )
}

/**
 * Clone ci-scripts git repo
 */
def void cloneCiScripts() {
  def scipipe = scipipeConfig()

  gitNoNoise(
    url: githubSlugToUrl(scipipe.ciscripts.github_repo),
    branch: scipipe.ciscripts.git_ref,
  )
}

/**
 * Clone git repo without generating a jenkins build changelog
 */
def void gitNoNoise(Map args) {
  git([
    url: args.url,
    branch: args.branch,
    changelog: false,
    poll: false
  ])
}

/**
 * Checkout a git ref (branch, tag or SHA)
*/
def checkoutGitRef(String url, String ref) {
  checkout([
    $class: 'GitSCM',
    branches: [[name: ref]],
    userRemoteConfigs: [[url: url]],
    doGenerateSubmoduleConfigurations: false,
    submoduleCfg: [],
      extensions: [
          [$class: 'CloneOption', noTags: false, shallow: false]
        ],
    changelog: false,
    poll: false
  ])
}

/**
 * Parse yaml file into object -- parsed files are memoized.
 *
 * @param file String file to parse
 * @return yaml Object
 */
// The @Memoized decorator seems to break pipeline serialization and this
// method can not be labeled as @NonCPS.
@Field Map yamlCache = [:]
def Object readYamlFile(String file) {
  def yaml = yamlCache[file] ?: readYaml(text: readFile(file))
  yamlCache[file] = yaml
  return yaml
}

/**
 * Build a multi-configuration matrix of eups tarballs.
 *
 * Example:
 *
 *     util.buildTarballMatrix(
 *       tarballConfigs: config.tarball.build_config,
 *       parameters: [
 *         PRODUCTS: tarballProducts,
 *         SMOKE: true,
 *         RUN_SCONS_CHECK: true,
 *         PUBLISH: true,
 *       ],
 *       retries: retries,
 *     )
 *
 * @param p Map
 * @param p.tarballConfigs List
 * @param p.parameters.PRODUCTS String
 * @param p.parameters.EUPS_TAG String
 * @param p.retries Integer Defaults to `1`.
 */
def void buildTarballMatrix(Map p) {
  requireMapKeys(p, [
    'tarballConfigs',
    'parameters',
  ])
  p = [
    retries: 1,
  ] + p

  requireMapKeys(p.parameters, [
    'PRODUCTS',
    'EUPS_TAG',
  ])

  def platform = [:]

  p.tarballConfigs.each { item ->
    def displayName = item.display_name ?: item.label
    def displayCompiler = item.display_compiler ?: item.compiler

    def splenvRef = item.splenv_ref
    if (p.parameters.SPLENV_REF) {
      splenvRef = p.parameters.SPLENV_REF
    }
    def rubinEnvVer = splenvRef
    if (p.parameters.RUBINENV_VER) {
      rubinEnvVer = p.parameters.RUBINENV_VER
    }

    def slug = "miniconda${item.python}"
    slug += "-${item.miniver}-${splenvRef}"

    def tarballBuild = {
      retry(p.retries) {
        build job: 'release/tarball',
          parameters: [
            string(name: 'PRODUCTS', value: p.parameters.PRODUCTS),
            string(name: 'EUPS_TAG', value: p.parameters.EUPS_TAG),
            booleanParam(name: 'SMOKE', value: p.parameters.SMOKE),
            booleanParam(
              name: 'RUN_SCONS_CHECK',
              value: p.parameters.RUN_SCONS_CHECK
            ),
            booleanParam(name: 'PUBLISH', value: p.parameters.PUBLISH),
            booleanParam(name: 'WIPEOUT', value: false),
            string(name: 'TIMEOUT', value: item.timelimit.toString()), // hours
            string(name: 'IMAGE', value: nullToEmpty(item.image)),
            string(name: 'LABEL', value: item.label),
            string(name: 'COMPILER', value: item.compiler),
            string(name: 'PYTHON_VERSION', value: item.python),
            string(name: 'MINIVER', value: item.miniver),
            string(name: 'SPLENV_REF', value: splenvRef),
            string(name: 'RUBINENV_VER', value: rubinEnvVer),
            string(name: 'OSFAMILY', value: item.osfamily),
            string(name: 'PLATFORM', value: item.platform),
          ]
      } // retry
    }

    platform["${displayName}.${displayCompiler}.${slug}"] = {
      if (item.allow_fail) {
        try {
          tarballBuild()
        } catch (e) {
          echo "giving up on build but suppressing error"
          echo e.toString()
        }
      } else {
        tarballBuild()
      }
    } // platform
  } // each

  parallel platform
} // buildTarballMatrix

/**
 * Convert null to empty string; pass through valid strings
 *
 * @param s String string to process
 */
@NonCPS
def String nullToEmpty(String s) {
  if (!s) { s = '' }
  s
}

/**
 * Convert an empty string to null; pass through valid strings
 *
 * @param s String string to process
 */
@NonCPS
def String emptyToNull(String s) {
  if (s == '') { s = null }
  s
}

/**
 * Convert UNIX epoch (seconds) to a UTC formatted date/time string.
 * @param epoch Integer count of seconds since UNIX epoch
 * @return String UTC formatted date/time string
 */
@NonCPS
def String epochToUtc(Integer epoch) {
  def unixTime = Instant.ofEpochSecond(epoch)
  instantToUtc(unixTime)
}

/**
 * Convert UNIX epoch (milliseconds) to a UTC formatted date/time string.
 * @param epoch Integer count of milliseconds since UNIX epoch
 * @return String UTC formatted date/time string
 */
@NonCPS
def String epochMilliToUtc(Long epoch) {
  def unixTime = Instant.ofEpochMilli(epoch)
  instantToUtc(unixTime)
}

/**
 * Convert java.time.Instant objects to a UTC formatted date/time string.
 * @param moment java.time.Instant object
 * @return String UTC formatted date/time string
 */
@NonCPS
def String instantToUtc(Instant moment) {
  def utcFormat = DateTimeFormatter
                    .ofPattern("yyyyMMdd'T'hhmmssX")
                    .withZone(ZoneId.of('UTC') )

  utcFormat.format(moment)
}

/**
 * Run librarian-puppet on the current directory via a container
 *
 * @param cmd String librarian-puppet arguments; defaults to 'install'
 * @param tag String tag of docker image to use.
 */
def void librarianPuppet(String cmd='install', String tag='2.2.3') {
  insideDockerWrap(
    image: "lsstsqre/cakepan:${tag}",
    args: "-e HOME=${pwd()}",
    pull: true,
  ) {
    bash "librarian-puppet ${cmd}"
  }
}

/**
 * run documenteer doc build
 *
 * @param p Map
 * @param p.docTemplateDir String path to sphinx template clone (required)
 * @param p.eupsTag String tag to setup (required)
 * @param p.eupsPath String path to EUPS installed productions (optional)
 * @param p.docImage String defaults to: 'lsstsqre/documenteer-base'
 * @param p.docPull Boolean defaults to: `false`
 */
def runDocumenteer(Map p) {
  requireMapKeys(p, [
    'docTemplateDir',
    'eupsTag',
  ])
  p = [
    docImage: null,
    docPull: false,
  ] + p

  def homeDir = "${pwd()}/home"
  emptyDirs([homeDir])

  def docEnv = [
    "HOME=${homeDir}",
    "EUPS_TAG=${p.eupsTag}",
  ]

  if (p.eupsPath) {
    docEnv += "EUPS_PATH=${p.eupsPath}"
  }

  withEnv(docEnv) {
    insideDockerWrap(
      image: p.docImage,
      pull: p.docPull,
    ) {
      dir(p.docTemplateDir) {
        bash '''
          source /opt/lsst/software/stack/loadLSST.bash
          dot -V
          pip install --upgrade --user -r requirements.txt
          export PATH="${HOME}/.local/bin:${PATH}"
          setup -r . -t "$EUPS_TAG"
          build-stack-docs -d . -v
        '''
      } // dir
    } // insideDockerWrap
  } // withEnv
} // runDocumenteer

/**
 * run ltd-mason-travis to push a doc build
 *
 * @param p Map
 * @param p.eupsTag String tag to setup (required). Eg.: 'current', 'b1234'
 * @param p.repoSlug String github repo slug (required). Eg.: 'lsst/pipelines_lsst_io'
 * @param p.ltdProduct String LTD product name (required)., Eg.: 'pipelines'
 * @param p.masonImage String docker image (optional). Defaults to: 'lsstsqre/ltd-mason'
 */
def ltdPush(Map p) {
  requireMapKeys(p, [
    'ltdSlug',
    'ltdProduct',
    'repoSlug',
  ])
  p = [
    masonImage: 'lsstsqre/ltd-mason',
  ] + p


  withEnv([
    "LTD_MASON_BUILD=true",
    "LTD_MASON_PRODUCT=${p.ltdProduct}",
    "LTD_KEEPER_URL=https://keeper.lsst.codes",
    "LTD_KEEPER_USER=travis",
    "TRAVIS_PULL_REQUEST=false",
    "TRAVIS_REPO_SLUG=${p.repoSlug}",
    "TRAVIS_BRANCH=${p.ltdSlug}",
  ]) {
    withCredentials([[
      $class: 'UsernamePasswordMultiBinding',
      credentialsId: 'ltd-mason-aws',
      usernameVariable: 'LTD_MASON_AWS_ID',
      passwordVariable: 'LTD_MASON_AWS_SECRET',
    ],
    [
      $class: 'UsernamePasswordMultiBinding',
      credentialsId: 'ltd-keeper',
      usernameVariable: 'LTD_KEEPER_USER',
      passwordVariable: 'LTD_KEEPER_PASSWORD',
    ]]) {
      docker.image(p.masonImage).inside {
        // expect that the service will return an HTTP 502, which causes
        // ltd-mason-travis to exit 1
        sh '''
        ltd-mason-travis --html-dir _build/html --verbose || true
        '''
      } // .inside
    } // withCredentials
  } //withEnv
} // ltdPush

/**
 * run `release/run-rebuild` job and parse result
 *
 * Example:
 *
 *     manifestId = util.runRebuild(
 *       parameters: [
 *         PRODUCTS: products,
 *         BUILD_DOCS: true,
 *       ],
 *     )
 *
 * @param p Map
 * @param p.job String job to trigger. Defaults to `release/run-rebuild`.
 * @param p.parameters Map
 * @param p.parameters.REFS String Defaults to `''`.
 * @param p.parameters.PRODUCTS String Defaults to `''`.
 * @param p.parameters.BUILD_DOCS Boolean Defaults to `false`.
 * @param p.parameters.TIMEOUT String Defaults to `'12'`.
 * @param p.parameters.PREP_ONLY Boolean Defaults to `false`.
 * @param p.parameters.SPLENV_REF String Optional
 * @return manifestId String
 */
def String runRebuild(Map p) {
  def useP = [
    job: 'release/run-rebuild',
  ] + p

  useP.parameters = [
    REFS: '',  // null is not a valid value for a string param
    PRODUCTS: '',
    BUILD_DOCS: false,
    TIMEOUT: '12', // should be String
    PREP_ONLY: false,
    NO_BINARY_FETCH: true,
  ] + p.parameters

  def jobParameters = [
          string(name: 'REFS', value: useP.parameters.REFS),
          string(name: 'PRODUCTS', value: useP.parameters.PRODUCTS),
          booleanParam(name: 'BUILD_DOCS', value: useP.parameters.BUILD_DOCS),
          booleanParam(name: 'NO_BINARY_FETCH', value: useP.parameters.NO_BINARY_FETCH),
          string(name: 'TIMEOUT', value: useP.parameters.TIMEOUT), // hours
          booleanParam(name: 'PREP_ONLY', value: useP.parameters.PREP_ONLY),
  ]

  // Optional parameter. Set 'em if you got 'em
  if (useP.parameters.SPLENV_REF) {
    jobParameters += string(name: 'SPLENV_REF', value: useP.parameters.SPLENV_REF)
  }

  def result = build(
    job: useP.job,
    parameters: jobParameters,
    wait: true,
  )

  nodeTiny {
    manifestArtifact = 'lsstsw/build/manifest.txt'

    step([$class: 'CopyArtifact',
          projectName: useP.job,
          filter: manifestArtifact,
          selector: [
            $class: 'SpecificBuildSelector',
            buildNumber: result.id,
          ],
        ])

    def manifestId = parseManifestId(readFile(manifestArtifact))
    echo "parsed manifest id: ${manifestId}"
    return manifestId
  } // nodeTiny
} // runRebuild

/*
 * Convert github "slug" to a URL.
 *
 * @param slug String
 * @param scheme String Defaults to 'https'.
 * @return url String
 */
@NonCPS
def String githubSlugToUrl(String slug, String scheme = 'https') {
  switch (scheme) {
    case 'https':
      return "https://github.com/${slug}"
      break
    case 'ssh':
      return "ssh://git@github.com/${slug}.git"
      break
    default:
      throw new Error("unknown scheme: ${scheme}")
  }
}

/*
 * Generate a github "raw" download URL.
 *
 * @param p.slug String
 * @param p.path String
 * @param p.ref String Defaults to 'main'
 * @return url String
 */
def String githubRawUrl(Map p) {
  requireMapKeys(p, [
    'slug',
    'path',
  ])
  def useP = [
    ref: 'main',
  ] + p

  def baseUrl = 'https://raw.githubusercontent.com'
  return "${baseUrl}/${useP.slug}/${useP.ref}/${useP.path}"
}

/*
 * Generate URL to versiondb manifest file.
 *
 * @param manifestId String
 * @return url String
 */
def String versiondbManifestUrl(String manifestId) {
  def scipipe = scipipeConfig()
  return githubRawUrl(
    slug: scipipe.versiondb.github_repo,
    path: "manifests/${manifestId}.txt",
  )
}

/*
 * Generate URL to repos.yaml.
 *
 * @return url String
 */
def String reposUrl() {
  def scipipe = scipipeConfig()
  return githubRawUrl(
    slug: scipipe.repos.github_repo,
    ref: scipipe.repos.git_ref,
    path: 'etc/repos.yaml',
  )
}

/*
 * Generate URL to lsstinstall
 *
 * @return url String
 */
def String lsstinstallUrl() {
  def scipipe = scipipeConfig()
  return githubRawUrl(
    slug: scipipe.newinstall.github_repo,
    ref: scipipe.newinstall.git_ref,
    path: 'scripts/lsstinstall',
  )
}

/*
 * Generate URL to shebangtron
 *
 * @return url String
 */
def String shebangtronUrl() {
  def scipipe = scipipeConfig()
  return githubRawUrl(
    slug: scipipe.shebangtron.github_repo,
    ref: scipipe.shebangtron.git_ref,
    path: 'shebangtron',
  )
}

/*
 * Sanitize string for use as docker tag
 *
 * @param tag String
 * @return tag String
 */
@NonCPS
def String sanitizeDockerTag(String tag) {
  // is there a canonical reference for the tag format?
  // convert / to -
  tag.tr('/', '_')
}

/**
 * Derive a "slug" string from a lsstsw build configuration Map.
 *
 * @param lsstswConfig Map
 * @return slug String
 */
@NonCPS
def String lsstswConfigSlug(Map lsstswConfig) {
  def lc = lsstswConfig
  def displayName = lc.display_name ?: lc.label
  def displayCompiler = lc.display_compiler ?: lc.compiler

  // Since we use conda compilers and Python 3, leave them out.
  // "${displayName}.${displayCompiler}.py${lc.python}"
  "${displayName}"
}

/*
 * Sanitize string for use as an eups tag
 *
 * @param tag String
 * @return tag String
 */
@NonCPS
def String sanitizeEupsTag(String tag) {
  // if the git tag is an official version, starts with a number
  // but eups tag need still to have 'v' in front
  char c = tag.charAt(0)
  if ( c.isDigit() ) {
    tag = "v" + tag
  }

  // eups doesn't like dots in tags, convert to underscores
  // by policy, we're not allowing dash either
  tag.tr('.-', '_')
}

/*
 * Get scipipe config
 *
 * @return config Object
 */
def Object scipipeConfig() {
  readYamlFile('etc/scipipe/build_matrix.yaml')
}

/*
 * Get sqre config
 *
 * @return config Object
 */
def Object sqreConfig() {
  readYamlFile('etc/sqre/config.yaml')
}

/*
 * Get ap_verify config
 *
 * @return config Object
 */
def Object apVerifyConfig() {
  readYamlFile('etc/scipipe/ap_verify.yaml')
}

/*
 * Get sims config
 *
 * @return config Object
 */
def Object simsConfig() {
  readYamlFile('etc/sims/config.yaml')
}

/*
 * Get verify_drp_metrics config
 *
 * @return config Object
 */
def Object verifyDrpMetricsConfig() {
  readYamlFile('etc/scipipe/verify_drp_metrics.yaml')
}

/*
 * Get default awscli docker image string
 *
 * @return awscliImage String
 */
def String defaultAwscliImage() {
  def dockerRegistry = sqreConfig().awscli.docker_registry
  "${dockerRegistry.repo}:${dockerRegistry.tag}"
}

/*
 * Get default gcloud docker image string
 *
 * @return gcloudImage String
 */
def String defaultGcloudImage() {
  def dockerRegistry = sqreConfig().gcloud.docker_registry
  "${dockerRegistry.repo}:${dockerRegistry.tag}"
}

/*
 * Get default codekit docker image string
 *
 * @return codekitImage String
 */
def String defaultCodekitImage() {
  def dockerRegistry = sqreConfig().codekit.docker_registry
  "${dockerRegistry.repo}:${dockerRegistry.tag}"
}

def Object runIndexUpdate(){
  def job = 'sqre/infra/update_indexjson'
  build(
    job: job,
    parameters:[
      string(name: 'ARCHITECTURE', value: 'linux-64'),
      string(name:'SPLENV_REF', value: scipipe.template.splenv_ref),
      string(name: 'MINI_VER', value: scipipe.template.tarball_defaults.miniver),
      booleanParam(
        name: 'NO_PUSH',
        value: scipipe.release.step.update_indexjson.no_push,
      ),],
    wait: true,
  ) // build

}

/**
 * run `release/docker/build-stack` job and parse result
 *
 * @param p.job Name of job to trigger. Defaults to
 *        `release/docker/build-stack`.
 * @param p.parameters.PRODUCTS String. Required.
 * @param p.parameters.EUPS_TAG String. Required.
 * @param p.parameters.MANIFEST_ID String. Required.
 * @param p.parameters.LSST_COMPILER String. Required.
 * @param p.parameters.NO_PUSH Boolean. Defaults to `false`.
 * @param p.parameters.TIMEOUT String. Defaults to `1'`.
 * @param p.parameters.SPLENV_REF String Optional
 * @return json Object
 */
def Object runBuildStack(Map p) {
  // validate p Map
  requireMapKeys(p, [
    'parameters',
  ])
  p = [
    job: 'release/docker/build-stack',
  ] + p

  // validate p.parameters Map
  requireMapKeys(p.parameters, [
    'PRODUCTS',
    'EUPS_TAG',
    // not required by the triggered job but as policy by this method.
    'MANIFEST_ID',
    'LSST_COMPILER',
  ])
  p.parameters = [
    NO_PUSH: false,
    TIMEOUT: '1', // should be String
    DOCKER_TAGS: '',  // null is not a valid value for a string param
  ] + p.parameters

  def jobParameters = [
    string(name: 'PRODUCTS', value: p.parameters.PRODUCTS),
    string(name: 'EUPS_TAG', value: p.parameters.EUPS_TAG),
    booleanParam(name: 'NO_PUSH', value: p.parameters.NO_PUSH),
    string(name: 'TIMEOUT', value: p.parameters.TIMEOUT),
    string(name: 'DOCKER_TAGS', value: p.parameters.DOCKER_TAGS),
    string(name: 'MANIFEST_ID', value: p.parameters.MANIFEST_ID),
    string(name: 'LSST_COMPILER', value: p.parameters.LSST_COMPILER),
  ]

  // Optional parameter. Set 'em if you got 'em
  if (p.parameters.SPLENV_REF) {
    jobParameters += string(name: 'SPLENV_REF', value: p.parameters.SPLENV_REF)
  }

  def result = build(
    job: p.job,
    parameters: jobParameters,
    wait: true
  )

  nodeTiny {
    resultsArtifact = 'results.json'

    step([
      $class: 'CopyArtifact',
      projectName: p.job,
      filter: resultsArtifact,
      selector: [
        $class: 'SpecificBuildSelector',
        buildNumber: result.id,
      ],
    ])

    def json = readJSON(file: resultsArtifact)
    echo "parsed ${resultsArtifact}: ${json}"
    return json
  } // nodeTiny
} // runBuildStack

/**
 * Sleep to ensure s3 objects have sync'd with the EUPS_PKGROOT.
 *
 * Example:
 *
 *     util.waitForS3()
 */
def void waitForS3() {
  def scipipe = scipipeConfig()

  stage('wait for s3 sync') {
    sleep(time: scipipe.release.s3_wait_time, unit: 'MINUTES')
  }
} // waitForS3

/**
 * Invoke block with eups related env vars.
 *
 * Example:
 *
 *     util.withEupsEnv {
 *       util.bash './dostuff.sh'
 *     }
 *
 * @param run Closure Invoked inside of wrapper container
 */
def void withEupsEnv(Closure run) {
  def scipipe = scipipeConfig()

  def baseUrl = scipipe.eups.base_url
  def s3Bucket = scipipe.eups.s3_bucket
  def gsBucket = scipipe.eups.gs_bucket
  withEnv([
    "EUPS_S3_BUCKET=${s3Bucket}",
    "EUPS_GS_BUCKET=${gsBucket}",
    "EUPS_BASE_URL=${baseUrl}",
  ]) {
    run()
  }
} // withEupsEnv

/**
 * Create/update a clone of an lfs enabled git repo.
 *
 * Example:
 *
 *     util.checkoutLFS(
 *       githubSlug: 'foo/bar',
 *       gitRef: 'main',
 *     )
 *
 * @param p Map
 * @param p.gitRepo String github repo slug
 * @param p.gitRef String git ref to checkout. Defaults to `main`
 */
def void checkoutLFS(Map p) {
  requireMapKeys(p, [
    'githubSlug',
    'gitRef',
  ])
  p = [
    gitRef: 'main',
  ] + p

  def gitRepo = githubSlugToUrl(p.githubSlug)

  def lfsImage = 'ghcr.io/lsst-dm/docker-newinstall'

  // running a git clone in a docker.inside block is broken
  checkoutGitRef(gitRepo, p.gitRef)

  try {
    insideDockerWrap(
      image: lfsImage,
      pull: true,
    ) {
      bash("""
      source /opt/lsst/software/stack/loadLSST.bash
      git lfs install --skip-repo
      git lfs pull origin
      """)
    }
  } finally {
    // try not to break jenkins clone mangement
    bash 'rm -f .git/hooks/post-checkout'
  }
} // checkoutLFS

/**
 * Download URL resource and write it to disk.
 *
 * Example:
 *
 *     util.downloadFile(
 *       url: 'https://example.org/foo/bar.baz',
 *       destFile: 'foo/bar.baz',
 *     )
 *
 * @param p Map
 * @param p.url String URL to fetch
 * @param p.destFile String path to write downloaded file
 */
def void downloadFile(Map p) {
  requireMapKeys(p, [
    'url',
    'destFile',
  ])

  writeFile(file: p.destFile, text: new URL(p.url).getText())
}

/**
 * Download `manifest.txt` from `lsst/versiondb`.
 *
 * Example:
 *
 *     util.downloadManifest(
 *       destFile: 'foo/manifest.txt',
 *       manifestId: 'b1234',
 *     )
 *
 * @param p Map
 * @param p.destFile String path to write downloaded file
 * @param p.manifestId String manifest build id aka bNNNN
 */
def void downloadManifest(Map p) {
  requireMapKeys(p, [
    'destFile',
    'manifestId',
  ])

  def manifestUrl = versiondbManifestUrl(p.manifestId)
  downloadFile(
    url: manifestUrl,
    destFile: p.destFile,
  )
}

/**
 * Download a copy of `repos.yaml`
 *
 * Example:
 *
 *     util.downloadRepos(
 *       destFile: 'foo/repos.yaml',
 *     )
 *
 * @param p Map
 * @param p.destFile String path to write downloaded file
 */
def void downloadRepos(Map p) {
  requireMapKeys(p, [
    'destFile',
  ])

  def reposUrl = reposUrl()
  downloadFile(
    url: reposUrl,
    destFile: p.destFile,
  )
}

/**
 * Collect artifacts
 *
 * Example:
 *
 *     // note: the whitespace is needed to prevent the example from exiting
 *     // the comment block -- not needed in real code
 *     util.record([
 *       "${runDir}/** /*.log",
 *       "${runDir}/** /*.json",
 *     ])
 *
 * @param archiveDirs List paths to be collected.
 */
def void record(List archiveDirs) {
  archiveDirs = relPath(pwd(), archiveDirs)

  archiveArtifacts([
    artifacts: archiveDirs.join(', '),
    excludes: '**/*.dummy',
    allowEmptyArchive: true,
    fingerprint: true
  ])
} // record

/**
 * Relativize a list of paths.
 *
 * Example:
 *
 *     util.relPath(pwd(), [
 *       "/foo/bar/baz/bonk",
 *       "/foo/bar/baz/quix",
 *     ])
 *
 * @param relativeToDir String base path
 * @param path List paths to be relativized
 * @return List of relativized paths
 */
def List relPath(String relativeToDir, List paths) {
  // convert to relative paths
  // https://gist.github.com/ysb33r/5804364
  def rootPath = Path.of(relativeToDir)
  return paths.collect { it ->
    // skip non-rel paths
    if (!it.startsWith('/')) { return it }
    rootPath.relativize(Path.of(it)).toString()
  }
} // relPath

/**
 * Relativize a list of paths.
 *
 * Example:
 *
 *     util.xz([
 *       '** /*.foo',
 *       '** /*.bar',
 *     ])
 *
 * @param patterns List of file patterns to compress
 * @return List of compressed files
 */
def List xz(List patterns) {
  patterns = relPath(pwd(), patterns)
  def files = patterns.collect { g -> findFiles(glob: g) }.flatten()
  def targetFile = 'compress_files.txt'
  writeFile(file: targetFile, text: files.join("\n") + "\n")

  // compressing an example hsc output file
  // (cmd)       (ratio)  (time)
  // xz -T0      0.183    0:20
  // xz -T0 -9   0.180    1:23
  // xz -T0 -9e  0.179    1:28

  // compress but do not remove original file
  util.bash "xz -T0 -9ev --keep --files=${targetFile}"
  return files.collect { f -> "${f}.xz" }
}

/**
 * Collect junit reports
 *
 * Example:
 *
 *     // note: the whitespace is needed to prevent the example from exiting
 *     // the comment block -- not needed in real code
 *     util.junit([
 *       "${runDir}/** /pytest-*.xml",
 *     ])
 *
 * @param testResults List paths to be collected.
 */
def void junit(List testResults) {
  testResults = relPath(pwd(), testResults)

  junit([
    testResults: testResults.join(', '),
    allowEmptyResults: true,
  ])
} // junit

/**
 * push results to squash using dispatch-verify.
 *
 * Example:
 *
 *     util.runDispatchVerify(
 *       runDir: runDir,
 *       lsstswDir: lsstswDir,
 *       datasetName: datasetName,
 *       resultFile: resultFile,
 *     )
 *
 * @param p Map
 * @param p.runDir String
 * @param p.lsstswDir String Path to (the fake) lsstsw dir
 * @param p.datasetName String The dataset name. Eg., validation_data_cfht
 * @param p.resultFile String [JSON] file to push to squash.
 */
def void runDispatchVerify(Map p) {
  util.requireMapKeys(p, [
    'runDir',
    'lsstswDir',
    'datasetName',
    'resultFile',
    'squashUrl',
  ])

  def run = {
    util.bash '''
      set +o xtrace
      source /opt/lsst/software/stack/loadLSST.bash
      setup verify
      set -o xtrace

      dispatch_verify.py \
        --env jenkins \
        --lsstsw "$LSSTSW_DIR" \
        --url "$SQUASH_URL" \
        --user "$SQUASH_USER" \
        --password "$SQUASH_PASS" \
        "$RESULT_FILE"
    '''
  } // run

  /*
  These are already present under pipeline:
  - BUILD_ID
  - BUILD_URL

  This var was defined automagically by matrixJob and now must be manually
  set:
  - dataset
  */
  withEnv([
    "LSSTSW_DIR=${p.lsstswDir}",
    "dataset=${p.datasetName}",
    "SQUASH_URL=${p.squashUrl}",
    "RESULT_FILE=${p.resultFile}",
  ]) {
    withCredentials([[
      $class: 'UsernamePasswordMultiBinding',
      credentialsId: 'squash-api-user',
      usernameVariable: 'SQUASH_USER',
      passwordVariable: 'SQUASH_PASS',
    ]]) {
      dir(p.runDir) {
        run()
      }
    } // withCredentials
  } // withEnv
} // runDispatchVerify

/**
 * Convert Gen 3 results into a form suitable for dispatch-verify.
 *
 * The output files are placed in runDir.
 *
 * Example:
 *
 *     util.runGen3ToJob(
 *       runDir: runDir,
 *       gen3Dir: gen3Dir,
 *       collectionName: collectionName,
 *       namespace: "",
 *       datasetName: datasetName,
 *     )
 *
 * @param p Map
 * @param p.runDir String
 * @param p.gen3Dir String Path to the Gen 3 repository
 * @param p.collectionName String The collection to search for metrics.
 * @param p.namespace String The metrics namespace to filter by, e.g. validate_drp, or "" for all metrics.
 * @param p.datasetName String The dataset name. Eg., validation_data_cfht
 */
def void runGen3ToJob(Map p) {
  util.requireMapKeys(p, [
    'gen3Dir',
    'collectionName',
    'namespace',
    'datasetName',
  ])

  def run = {
    util.bash '''
      set +o xtrace
      source /opt/lsst/software/stack/loadLSST.bash
      setup verify
      set -o xtrace

      if [[ -n $METRIC_NAMESPACE ]]
        then gen3_to_job.py \
          "$REPO_DIR" \
          "$OUTPUT_COLLECTION" \
          --metrics_package "$METRIC_NAMESPACE" \
          --dataset_name "$dataset"
        else gen3_to_job.py \
          "$REPO_DIR" \
          "$OUTPUT_COLLECTION" \
          --dataset_name "$dataset"
      fi
    '''
  } // run

  /*
  These are already present under pipeline:
  - BUILD_ID
  - BUILD_URL

  This var was defined automagically by matrixJob and now must be manually
  set:
  - dataset
  */
  withEnv([
    "REPO_DIR=${p.gen3Dir}",
    "OUTPUT_COLLECTION=${p.collectionName}",
    "METRIC_NAMESPACE=${p.namespace}",
    "dataset=${p.datasetName}",
  ]) {
    dir(p.runDir) {
      run()
    }
  } // withEnv
} // runGen3ToJob

/**
 * push results to sasquatch using verify_to_sasquatch.
 *
 *     util.runVerifyToSasquatch(
 *       runDir: runDir,
 *       gen3Dir: gen3Dir,
 *       collectionName: collectionName,
 *       namespace: "lsst.example",
 *       datasetName: "ci_example",
 *       sasquatchUrl: util.sqreConfig().sasquatch.url,
 *       branchRefs: "tickets/DM-12345 tickets/DM-67890",
 *       pipeline: "SingleFrame.yaml",
 *     )
 * @param p Map
 * @param p.runDir String
 * @param p.gen3Dir String Path to the Gen 3 repository
 * @param p.collectionName String The collection to search for metrics.
 * @param p.namespace String The Sasquatch namespace to push to, e.g., lsst.dm.
 * @param p.datasetName String The dataset name. Eg., validation_data_cfht
 * @param p.sasquatchUrl String The URL to the Sasquatch REST proxy.
 * @param p.branchRefs String The branch(es) used in the run, as a space-delimited string (optional).
 * @param p.pipeline String The pipeline used in the run (optional).
 */
def void runVerifyToSasquatch(Map p) {
  util.requireMapKeys(p, [
    'runDir',
    'gen3Dir',
    'collectionName',
    'namespace',
    'datasetName',
    'sasquatchUrl',
  ])

  def run = {
    util.bash '''
      set +o xtrace
      source /opt/lsst/software/stack/loadLSST.bash
      setup analysis_tools
      set -o xtrace

      verify_to_sasquatch.py \
          "$REPO_DIR" \
          "$OUTPUT_COLLECTION" \
          --dataset "$dataset" \
          --url "$SASQUATCH_URL" \
          --namespace "$SASQUATCH_NAMESPACE" \
          --extra "ci_id=$BUILD_ID" \
          --extra "ci_url=$BUILD_URL" \
          --extra "ci_name=$JOB_NAME" \
          --extra "ci_refs=$JOB_REFS" \
          --extra "pipeline=$JOB_PIPELINE"
    '''
  } // run

  /*
  These are already present under pipeline:
  - BUILD_ID
  - BUILD_URL
  - JOB_NAME

  This var was defined automagically by matrixJob and now must be manually
  set:
  - dataset
  */
  withEnv([
    "REPO_DIR=${p.gen3Dir}",
    "OUTPUT_COLLECTION=${p.collectionName}",
    "SASQUATCH_NAMESPACE=${p.namespace}",
    "dataset=${p.datasetName}",
    "SASQUATCH_URL=${p.sasquatchUrl}",
    "JOB_REFS=${p.containsKey('branchRefs') ? p.branchRefs : ''}",
    "JOB_PIPELINE=${p.containsKey('pipeline') ? p.pipeline : ''}",
  ]) {
    // TODO: need Sasquatch authentication eventually; verify_to_sasquatch.py takes a --token arg
    // withCredentials([[
    //   $class: 'UsernamePasswordMultiBinding',
    //   credentialsId: 'squash-api-user',
    //   usernameVariable: 'SQUASH_USER',
    //   passwordVariable: 'SQUASH_PASS',
    // ]]) {
      dir(p.runDir) {
        run()
      }
    // } // withCredentials
  } // withEnv
} // runDispatchVerify

/**
 * Create a "fake" lsstsw-ish dir structure as expected by
 * `dispatch-verify.py`, which includes a `manifest.txt` and a copy of
 * `repos.yaml`.
 *
 * Example:
 *
 *     util.createFakeLsstswClone(
 *       fakeLsstswDir: fakeLsstswDir,
 *       manifestId: manifestId,
 *     )
 *
 * @param p Map
 * @param p.fakeLsstswDir String dir path
 * @param p.manifestId String versiondb manifest id
 */
def void createFakeLsstswClone(Map p) {
  requireMapKeys(p, [
    'fakeLsstswDir',
    'manifestId',
  ])

  def fakeLsstswDir    = p.fakeLsstswDir
  def fakeManifestDir  = "${fakeLsstswDir}/build"
  def fakeManifestFile = "${fakeManifestDir}/manifest.txt"
  def fakeReposDir     = "${fakeLsstswDir}/etc"
  def fakeReposFile    = "${fakeReposDir}/repos.yaml"

  emptyDirs([
    fakeManifestDir,
    fakeReposDir,
  ])

  downloadManifest(
    destFile: fakeManifestFile,
    manifestId: p.manifestId,
  )
  downloadRepos(destFile: fakeReposFile)
} // createFlakeLsstwClone

/**
 * Validate that a map has the minimum required set of keys for an lsstsw
 * build env configuration.
 *
 * Example:
 *
 *     util.validateLsstswConfig(lsstswConfig)
 *
 * @param p Map
 */
def void validateLsstswConfig(Map conf) {
  requireMapKeys(conf, [
    'compiler',
    'image',
    'label',
    'python',
    'splenv_ref',
  ])
}

/**
 * If running on kubernetes, report basic information about the k8s pod.
 *
 * Example:
 *
 *     util.printK8sVars()
 *
 */
def void printK8sVars() {
  // env.getEnvronment() returns vars groovy will set but not the current node env
  // System.getenv() returns the manager's env
  // env.<foo> works as this uses magic to check the actual env

  // test to see if the agent has k8s env vars
  if (env.K8S_NODE_NAME) {
    echo 'agent appears to be running on kubernetes...'
    // if so, list them using a shell as there is currently no other practical
    // way to iterate over the complete set of env vars.
    bash 'printenv | grep ^K8S_ | sort'
  }
}

/**
 * Run generic block
 *
 * Example:
 *
 *     util.nodeWrap { ... }
 *
 * @param run Closure Invoked inside of node step
 */
def void nodeWrap(Closure run) {
  nodeWrap(null) { run() }
}

/**
 * Run generic block
 *
 * Example:
 *
 *     util.nodeWrap('linux-64') { ... }
 *
 * @param label String Label expression
 * @param run Closure Invoked inside of node step
 */
def void nodeWrap(String label, Closure run) {
  node(label) {
    printK8sVars()
    run()
  }
}

return this;
