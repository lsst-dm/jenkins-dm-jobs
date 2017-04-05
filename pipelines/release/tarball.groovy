import groovy.transform.Field

class UnsupportedCompiler extends Exception {}

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

@Field String newinstall_url = 'https://raw.githubusercontent.com/lsst/lsst/master/scripts/newinstall.sh'

try {
  notify.started()
  def retries = 1

  stage('build tarballs') {
    def platform = [:]

    platform['linux - el6'] = {
      retry(retries) {
        def imageName = 'lsstsqre/centos:6-newinstall'
        linuxTarballs(imageName, 'el6', 'devtoolset-3')
      }
    }

    platform['linux - el7'] = {
      retry(retries) {
        def imageName = 'lsstsqre/centos:7-newinstall'
        linuxTarballs(imageName, 'el7', 'gcc-system')
      }
    }

    platform['osx - 10.11'] = {
      retry(retries) {
        osxBuild('10.11', 'clang-800.0.42.1')
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

def void linuxTarballs(String imageName, String platform, String compiler) {
  node('docker') {
    // these "credentials" aren't secrets -- just a convient way of setting
    // globals for the instance. Thus, they don't need to be tightly scoped to a
    // single sh step
    withCredentials([[
      $class: 'StringBinding',
      credentialsId: 'cmirror-s3-bucket',
      variable: 'CMIRROR_S3_BUCKET'
    ],
    [
      $class: 'StringBinding',
      credentialsId: 'eups-push-bucket',
      variable: 'EUPS_S3_BUCKET'
    ]]) {
      dir(platform) {
        docker.image(imageName).pull()
        linuxBuild(imageName, compiler)
        // XXX demo isn't yet working
        // linuxDemo(imageName)
        s3Push('redhat', platform, compiler)
      }
    } // withCredentials([[
  }
}

def void linuxBuild(String imageName, String compiler) {
  try {
    def shName = 'scripts/run.sh'
    prepare(PRODUCT, EUPS_TAG, shName, '/distrib', compiler) // path inside build container

    withEnv(["RUN=${shName}", "IMAGE=${imageName}"]) {
      shColor '''
        set -e

        chmod a+x "$RUN"
        docker run -t \
          -v "$(pwd)/scripts:/scripts" \
          -v "$(pwd)/distrib:/distrib" \
          -v "$(pwd)/build:/build" \
          -w /build \
          -e CMIRROR_S3_BUCKET="$CMIRROR_S3_BUCKET" \
          -e EUPS_S3_BUCKET="$EUPS_S3_BUCKET" \
          "$IMAGE" \
          sh -c "/${RUN}"
      '''
    }
  } finally {
    cleanupDocker(imageName)
  }
}

def void linuxDemo(String imageName, String compiler) {
  try {
    def shName = 'scripts/demo.sh'
    prepare(PRODUCT, EUPS_TAG, shName, '/distrib', compiler) // path inside build container

    dir('buildbot-scripts') {
      git([
        url: 'https://github.com/lsst-sqre/buildbot-scripts.git',
        branch: 'master'
      ])
    }

    withEnv(["RUN=${shName}", "IMAGE=${imageName}"]) {
      shColor '''
        set -e

        chmod a+x "$RUN"
        docker run -t \
          -v "$(pwd)/scripts:/scripts" \
          -v "$(pwd)/distrib:/distrib" \
          -v "$(pwd)/demo:/demo" \
          -v "$(pwd)/buildbot-scripts:/buildbot-scripts" \
          -w /demo \
          -e CMIRROR_S3_BUCKET="$CMIRROR_S3_BUCKET" \
          -e EUPS_S3_BUCKET="$EUPS_S3_BUCKET" \
          "$IMAGE" \
          sh -c "/${RUN}"
      '''
    }
  } finally {
    cleanupDocker(imageName)
  }
}

def void osxBuild(String platform, String compiler) {
  node("osx-${platform}") {
    // these "credentials" aren't secrets -- just a convient way of setting
    // globals for the instance. Thus, they don't need to be tightly scoped to a
    // single sh step
    withCredentials([[
      $class: 'StringBinding',
      credentialsId: 'cmirror-s3-bucket',
      variable: 'CMIRROR_S3_BUCKET'
    ],
    [
      $class: 'StringBinding',
      credentialsId: 'eups-push-bucket',
      variable: 'EUPS_S3_BUCKET'
    ]]) {
      dir(platform) {
        try {
          def shName = 'scripts/run.sh'
          prepare(PRODUCT, EUPS_TAG, shName, "./distrib", compiler)

          shColor """
            set -e

            chmod a+x "${shName}"
            "${shName}"
          """

          s3Push('osx', platform, compiler)
        } finally {
          cleanup()
        }
      } // dir(platform)
    } // withCredentials([[
  } // node
}

def void prepare(String product, String eupsTag, String shName, String distribDir, String compiler) {
  def script = buildScript(product, eupsTag, distribDir, compiler)

  shColor 'mkdir -p distrib scripts build'
  writeFile(file: shName, text: script)
}

def void prepareDemo(String product, String eupsTag, String shName, String distribDir, String compiler) {
  def script = demoScript(product, eupsTag, distribDir)

  shColor 'mkdir -p demo'
  writeFile(file: shName, text: script)
}

def void s3Push(String osfamily, String platform, String compiler) {
  shColor '''
    set -e
    # do not assume virtualenv is present
    pip install virtualenv
    virtualenv venv
    . venv/bin/activate
    pip install awscli
  '''

  withCredentials([[
    $class: 'UsernamePasswordMultiBinding',
    credentialsId: 'aws-eups-push',
    usernameVariable: 'AWS_ACCESS_KEY_ID',
    passwordVariable: 'AWS_SECRET_ACCESS_KEY'
  ]]) {
    shColor """
      set -e
      . venv/bin/activate
      aws s3 sync ./distrib/ s3://\$EUPS_S3_BUCKET/stack/${osfamily}/${platform}/${compiler}
    """
  }
}

def void cleanup() {
  shColor 'rm -rf "./build/.lockDir"'
}

// because the uid in the docker container probably won't match the
// jenkins-slave user, the bind volume mounts end up with file ownerships that
// prevent them from being deleted.
def void cleanupDocker(String imageName) {
  withEnv(["IMAGE=${imageName}"]) {
    shColor '''
      docker run -t \
        -v "$(pwd)/build:/build" \
        -w /build \
        "$IMAGE" \
        rm -rf /build/.lockDir
    '''
    /*
    shColor '''
      docker run -t \
        -v "$(pwd)/demo:/demo" \
        -w /build \
        "$IMAGE" \
        rm -rf /demo
    '''
    */
  }
}

def void shColor(script) {
  wrap([$class: 'AnsiColorBuildWrapper']) {
    sh dedent(script)
  }
}

// XXX the dynamic build script construction has evolved into a fair number of
// nested steps and this may be difficult to comprehend in the future.
// Consider moving all of this logic into an external driver script that is
// called with parameters.
@NonCPS
def String buildScript(String products, String tag, String eupsPkgroot, String compiler) {
  scriptPreamble(compiler) +
  dedent("""
    curl -sSL ${newinstall_url} | bash -s -- -cb
    . ./loadLSST.bash

    eups distrib install ${products} -t "${tag}" -vvv

    export EUPS_PKGROOT="${eupsPkgroot}"
    for product in "${products}"; do
      eups distrib create --server-dir "\$EUPS_PKGROOT" -d tarball "\$product" -t "${tag}" -vvv
    done
    eups distrib declare --server-dir "\$EUPS_PKGROOT" -t "${tag}" -vvv
  """)
}

@NonCPS
def String demoScript(String products, String tag, String eupsPkgroot, String compiler) {
  scriptPreamble(compiler) +
  dedent("""
    export EUPS_PKGROOT="${eupsPkgroot}"

    curl -sSL ${newinstall_url} | bash -s -- -cb
    . ./loadLSST.bash

    # XXX the lsst product is declaring EUPS_PKGROOT
    export EUPS_PKGROOT="${eupsPkgroot}"

    eups distrib install ${products} -t "${tag}" -vvv

    /buildbot-scripts/runManifestDemo.sh --tag "${tag}" --small
  """)
}

@NonCPS
def String scriptPreamble(String compiler) {
  dedent("""
    set -e

    if [[ -n \$CMIRROR_S3_BUCKET ]]; then
        export CONDA_CHANNELS="http://\${CMIRROR_S3_BUCKET}/pkgs/free"
        export MINICONDA_BASE_URL="http://\${CMIRROR_S3_BUCKET}/miniconda"
    fi

    if [[ -n \$EUPS_S3_BUCKET ]]; then
        export EUPS_PKGROOT_BASE_URL="https://\${EUPS_S3_BUCKET}/stack"
    fi

    # isolate eups cache files
    export EUPS_USERDATA="\${PWD}/.eups"
    """
    + scriptCompiler(compiler)
  )
}

@NonCPS
def String dedent(String text) {
  if (text == null) {
    return null
  }
  text.replaceFirst("\n","").stripIndent()
}

@NonCPS
def String scriptCompiler(String compiler) {
  def setup = null
  switch(compiler) {
    case ~/^devtoolset-\d/:
      setup = """
        enable_script=/opt/rh/${compiler}/enable
        if [[ ! -e \$enable_script ]]; then
          echo "devtoolset enable script is missing"
          exit 1
        fi
        set -o verbose
        . \$enable_script
        set +o verbose
      """
      break
    case 'gcc-system':
      setup = '''
        cc_path=$(type -p gcc)
        if [[ -z $cc_path ]]; then
          echo "compiler appears to be missing from PATH"
          exit 1
        fi
        if [[ $cc_path != '/usr/bin/gcc' ]]; then
          echo "system compiler is not default"
          exit 1
        fi
      '''
      break
    case ~/^clang-.*/:
      setup = """
        target_cc_version=$compiler
        cc_path=\$(type -p clang)
        if [[ -z \$cc_path ]]; then
          echo "compiler appears to be missing from PATH"
          exit 1
        fi
        if [[ \$cc_path != '/usr/bin/clang' ]]; then
          echo "system compiler is not default"
          exit 1
        fi

        # Apple LLVM version 8.0.0 (clang-800.0.42.1)
        if [[ ! \$(clang --version) =~ Apple[[:space:]]+LLVM[[:space:]]+version[[:space:]]+[[:digit:].]+[[:space:]]+\\((.*)\\) ]]; then
          echo "unable to determine compiler version"
          exit 1
        fi
        cc_version="\${BASH_REMATCH[1]}"

        if [[ \$cc_version != \$target_cc_version ]]; then
          echo "found clang \$cc_version but expected \$target_cc_version"
          exit 1
        fi
      """
      break
    case null:
    default:
      throw new UnsupportedCompiler(compiler)
  }

  dedent(setup)
}
