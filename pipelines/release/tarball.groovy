def notify = null
node {
  dir('jenkins-dm-jobs') {
    // XXX the git step seemed to blowup on a branch of '*/<foo>'
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs()
    ])
    notify = load 'pipelines/lib/notify.groovy'
  }
}

try {
  notify.started()
  def retries = 1

  stage('build tarballs') {
    def platform = [:]

    platform['linux - el6'] = {
      retry(retries) {
        def imageName = 'lsstsqre/centos:6-newinstall'
        linuxTarballs(imageName, 'el6')
      }
    }

    platform['linux - el7'] = {
      retry(retries) {
        def imageName = 'lsstsqre/centos:7-newinstall'
        linuxTarballs(imageName, 'el7')
      }
    }

    platform['osx - 10.11'] = {
      retry(retries) {
        osxBuild('10.11')
      }
    }

    parallel platform
  }
} catch (e) {
  // If there was an exception thrown, the build failed
  currentBuild.result = "FAILED"
  throw e
} finally {
  echo "result: ${currentBuild.result}"
  switch(currentBuild.result) {
    case null:
    case 'SUCCESS':
      notify.success()
      break
    case 'ABORTED':
      notify.aborted()
      break
    case 'FAILURE':
      notify.failure()
      break
    default:
      notify.failure()
  }
}

def linuxTarballs(String imageName, String version) {
  node('docker') {
    dir(version) {
      docker.image(imageName).pull()
      linuxBuild(imageName, version)
      linuxDemo(imageName, version)
      s3Push('linux', version)
    }
  }
}

def linuxBuild(String imageName, String version) {
  try {
    def shName = 'scripts/run.sh'
    prepare(PRODUCT, EUPS_TAG, shName, '/distrib') // path inside build container

    withCredentials([[
      $class: 'StringBinding',
      credentialsId: 'cmirror-s3-bucket',
      variable: 'CMIRROR_S3_BUCKET'
    ]]) {
      withEnv(["RUN=${shName}", "IMAGE=${imageName}"]) {
        sh '''
          set -e

          chmod a+x "$RUN"
          docker run -t \
            -v "$(pwd)/scripts:/scripts" \
            -v "$(pwd)/distrib:/distrib" \
            -v "$(pwd)/build:/build" \
            -w /build \
            -e CMIRROR_S3_BUCKET="$CMIRROR_S3_BUCKET" \
            "$IMAGE" \
            sh -c "/${RUN}"
        '''.replaceFirst("\n","").stripIndent()
      }
    }
  } finally {
    cleanupDocker(imageName)
  }
}

def linuxDemo(String imageName, String version) {
  try {
    def shName = 'scripts/demo.sh'
    prepare(PRODUCT, EUPS_TAG, shName, '/distrib') // path inside build container

    dir('buildbot-scripts') {
      git([
        url: 'https://github.com/lsst-sqre/buildbot-scripts.git',
        branch: 'master'
      ])
    }

    withCredentials([[
      $class: 'StringBinding',
      credentialsId: 'cmirror-s3-bucket',
      variable: 'CMIRROR_S3_BUCKET'
    ]]) {
      withEnv(["RUN=${shName}", "IMAGE=${imageName}"]) {
        sh '''
          set -e

          chmod a+x "$RUN"
          docker run -t \
            -v "$(pwd)/scripts:/scripts" \
            -v "$(pwd)/distrib:/distrib" \
            -v "$(pwd)/demo:/demo" \
            -v "$(pwd)/buildbot-scripts:/buildbot-scripts" \
            -w /demo \
            -e CMIRROR_S3_BUCKET="$CMIRROR_S3_BUCKET" \
            "$IMAGE" \
            sh -c "/${RUN}"
        '''.replaceFirst("\n","").stripIndent()
      }
    }
  } finally {
    cleanupDocker(imageName)
  }
}

def osxBuild(String version) {
  node("osx-${version}") {
    dir(version) {
      try {
        def shName = 'scripts/run.sh'
        prepare(PRODUCT, EUPS_TAG, shName, "./distrib")

        withCredentials([[
          $class: 'StringBinding',
          credentialsId: 'cmirror-s3-bucket',
          variable: 'CMIRROR_S3_BUCKET'
        ]]) {
          shColor """
            set -e

            chmod a+x "${shName}"
            "${shName}"
          """.replaceFirst("\n","").stripIndent()
        }

        s3Push('osx', version)
      } finally {
        cleanup()
      }
    } // dir(version)
  } // node
}

def prepare(String product, String eupsTag, String shName, String distribDir) {
  def script = buildScript(product, eupsTag, distribDir)

  shColor 'mkdir -p distrib scripts build'
  writeFile(file: shName, text: script)
}

def prepareDemo(String product, String eupsTag, String shName, String distribDir) {
  def script = demoScript(product, eupsTag, distribDir)

  shColor 'mkdir -p demo'
  writeFile(file: shName, text: script)
}

def s3Push(String platform, String version) {
  shColor '''
    set -e
    # do not assume virtualenv is present
    pip install virtualenv
    virtualenv venv
    . venv/bin/activate
    pip install awscli
  '''.replaceFirst("\n","").stripIndent()

  withCredentials([[
    $class: 'UsernamePasswordMultiBinding',
    credentialsId: 'aws-eups-push',
    usernameVariable: 'AWS_ACCESS_KEY_ID',
    passwordVariable: 'AWS_SECRET_ACCESS_KEY'
  ],
  [
    $class: 'StringBinding',
    credentialsId: 'eups-push-bucket',
    variable: 'EUPS_S3_BUCKET'
  ]]) {
    shColor """
      set -e
      . venv/bin/activate
      aws s3 sync ./distrib/ s3://\$EUPS_S3_BUCKET/stack/${platform}/${version}/
    """.replaceFirst("\n","").stripIndent()
  }
}

def cleanup() {
  shColor 'rm -rf "./build/.lockDir"'
}

// because the uid in the docker container probably won't match the
// jenkins-slave user, the bind volume mounts end up with file ownerships that
// prevent them from being deleted.
def cleanupDocker(String imageName) {
  withEnv(["IMAGE=${imageName}"]) {
    shColor '''
      docker run -t \
        -v "$(pwd)/build:/build" \
        -w /build \
        "$IMAGE" \
        rm -rf /build/.lockDir
    '''.replaceFirst("\n","").stripIndent()
    shColor '''
      docker run -t \
        -v "$(pwd)/demo:/demo" \
        -w /build \
        "$IMAGE" \
        rm -rf /demo
    '''.replaceFirst("\n","").stripIndent()
  }
}

def shColor(script) {
  wrap([$class: 'AnsiColorBuildWrapper']) {
    sh script
  }
}

@NonCPS
def buildScript(String products, String tag, String eupsPkgroot) {
  """
    set -e

    if [[ -n \$CMIRROR_S3_BUCKET ]]; then
        export CONDA_CHANNELS="http://\${CMIRROR_S3_BUCKET}/pkgs/free"
        export MINICONDA_BASE_URL="http://\${CMIRROR_S3_BUCKET}/miniconda"
    fi

    set -o verbose
    if grep -q -i "CentOS release 6" /etc/redhat-release; then
      . /opt/rh/devtoolset-3/enable
    fi
    set +o verbose

    curl -sSL https://raw.githubusercontent.com/lsst/lsst/master/scripts/newinstall.sh | bash -s -- -cb
    . ./loadLSST.bash
    eups distrib install ${products} -t "${tag}" -vvv

    export EUPS_PKGROOT="${eupsPkgroot}"
    for product in "${products}"; do
      eups distrib create --server-dir "\$EUPS_PKGROOT" -d tarball "\$product" -t "${tag}" -vvv
    done
    eups distrib declare --server-dir "\$EUPS_PKGROOT" -t "${tag}" -vvv
  """.replaceFirst("\n","").stripIndent()
}

@NonCPS
def demoScript(String products, String tag, String eupsPkgroot) {
  """
    set -e

    if [[ -n \$CMIRROR_S3_BUCKET ]]; then
        export CONDA_CHANNELS="http://\${CMIRROR_S3_BUCKET}/pkgs/free"
        export MINICONDA_BASE_URL="http://\${CMIRROR_S3_BUCKET}/miniconda"
    fi

    set -o verbose
    if grep -q -i "CentOS release 6" /etc/redhat-release; then
      . /opt/rh/devtoolset-3/enable
    fi
    set +o verbose

    export EUPS_PKGROOT="${eupsPkgroot}"

    curl -sSL https://raw.githubusercontent.com/lsst/lsst/master/scripts/newinstall.sh | bash -s -- -cb
    . ./loadLSST.bash

    # XXX the lsst product is declaring EUPS_PKGROOT
    export EUPS_PKGROOT="${eupsPkgroot}"

    eups distrib install ${products} -t "${tag}" -vvv

    /buildbot-scripts/runManifestDemo.sh --tag "${tag}" --small
  """.replaceFirst("\n","").stripIndent()
}
