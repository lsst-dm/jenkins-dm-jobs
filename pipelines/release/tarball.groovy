import groovy.transform.Field

class UnsupportedCompiler extends Exception {}

def notify = null
node {
  if (params.WIPEOUT) {
    deleteDir()
  }

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

  def pyenvs = [
    new MinicondaEnv('2', '4.2.12', '7c8e67'),
    new MinicondaEnv('3', '4.2.12', '7c8e67'),
  ]

  for (py in pyenvs) {
    stage("build ${py.slug()} tarballs") {
      def platform = [:]

      // el6/py3 is broken
      // https://jira.lsstcorp.org/browse/DM-10272
      if (py.pythonVersion == '2') {
        platform['el6'] = {
          retry(retries) {
            def imageName = 'lsstsqre/centos:6-newinstall'
            linuxTarballs(imageName, 'el6', 'devtoolset-3', py)
          }
        }
      }

      platform['el7'] = {
        retry(retries) {
          def imageName = 'lsstsqre/centos:7-newinstall'
          linuxTarballs(imageName, 'el7', 'gcc-system', py)
        }
      }

      platform['osx-10.11'] = {
        retry(retries) {
          osxTarballs('10.11', '10.9', 'clang-800.0.42.1', py)
        }
      }

      parallel platform
    }
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

def void linuxTarballs(
  String imageName,
  String platform,
  String compiler,
  MinicondaEnv menv
) {
  def String slug = menv.slug()
  def envId = joinPath('redhat', platform, compiler, slug)

  node('docker') {
    if (params.WIPEOUT) {
      deleteDir()
    }

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
      dir(envId) {
        docker.image(imageName).pull()
        linuxBuild(imageName, compiler, menv)
        if (params.SMOKE) {
          linuxSmoke(imageName, compiler, menv)
        }

        if (params.PUBLISH) {
          s3Push(envId)
        }
      }
    } // withCredentials([[
  }
}

def void osxTarballs(
  String platform,
  String macosx_deployment_target,
  String compiler,
  MinicondaEnv menv
) {
  def String slug = menv.slug()
  def envId = joinPath('osx', macosx_deployment_target, compiler, slug)

  node("osx-${platform}") {
    if (params.WIPEOUT) {
      deleteDir()
    }

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
      dir(envId) {
        osxBuild(macosx_deployment_target, compiler, menv)

        if (params.SMOKE) {
          osxSmoke(macosx_deployment_target, compiler, menv)
        }

        if (params.PUBLISH) {
          s3Push(envId)
        }
      } // dir(platform)
    } // withCredentials([[
  } // node
}

def void linuxBuild(String imageName, String compiler, MinicondaEnv menv) {
  try {
    def shBasename = 'run.sh'
    def shPath = "${pwd()}/scripts"
    def shName = "${shPath}/${shBasename}"
    def localImageName = "${imageName}-local"

    shColor 'mkdir -p distrib build scripts'

    prepareBuild(
      params.PRODUCT,
      params.EUPS_TAG,
      shName,
      '/distrib', // path inside container
      compiler,
      null,
      menv
    )

    wrapContainer(imageName, localImageName)

    withEnv(["RUN=/scripts/${shBasename}", "IMAGE=${localImageName}"]) {
      shColor '''
        set -e

        docker run \
          --storage-opt size=100G \
          -v "$(pwd)/scripts:/scripts" \
          -v "$(pwd)/distrib:/distrib" \
          -v "$(pwd)/build:/build" \
          -w /build \
          -e CMIRROR_S3_BUCKET="$CMIRROR_S3_BUCKET" \
          -e EUPS_S3_BUCKET="$EUPS_S3_BUCKET" \
          -u "$(id -u -n)" \
          "$IMAGE" \
          sh -c "$RUN"
      '''
    }
  } finally {
    cleanup()
  }
}

def void osxBuild(
  String macosx_deployment_target,
  String compiler,
  MinicondaEnv menv
) {
  try {
    def shName = "${pwd()}/scripts/run.sh"

    shColor 'mkdir -p distrib build scripts'

    prepareBuild(
      params.PRODUCT,
      params.EUPS_TAG,
      "${shName}",
      "${pwd()}/distrib",
      compiler,
      macosx_deployment_target,
      menv
    )

    dir('build') {
      shColor """
        set -e

        "${shName}"
      """
    }
  } finally {
    cleanup()
  }
}

def void linuxSmoke(String imageName, String compiler, MinicondaEnv menv) {
  def shBasename = 'run.sh'
  def shPath = "${pwd()}/scripts"
  def shName = "${shPath}/${shBasename}"
  def localImageName = "${imageName}-local"

  // smoke state is left at the end of the build for possible debugging but
  // each test needs to be run in a clean env.
  dir('smoke') {
    deleteDir()
  }

  shColor 'mkdir -p smoke'

  prepareSmoke(
    params.PRODUCT,
    params.EUPS_TAG,
    shName,
    '/distrib', // path inside container
    compiler,
    null,
    menv,
    '/buildbot-scripts' // path inside container
  )

  dir('buildbot-scripts') {
    git([
      url: 'https://github.com/lsst-sqre/buildbot-scripts.git',
      branch: 'master'
    ])
  }

  wrapContainer(imageName, localImageName)

  withEnv([
    "RUN=/scripts/${shBasename}",
    "IMAGE=${localImageName}",
    "RUN_DEMO=${params.RUN_DEMO}",
  ]) {
    shColor '''
      set -e

      docker run \
        --storage-opt size=100G \
        -v "$(pwd)/scripts:/scripts" \
        -v "$(pwd)/distrib:/distrib" \
        -v "$(pwd)/buildbot-scripts:/buildbot-scripts" \
        -v "$(pwd)/smoke:/smoke" \
        -w /smoke \
        -e CMIRROR_S3_BUCKET="$CMIRROR_S3_BUCKET" \
        -e EUPS_S3_BUCKET="$EUPS_S3_BUCKET" \
        -e RUN_DEMO="$RUN_DEMO" \
        -u "$(id -u -n)" \
        "$IMAGE" \
        sh -c "$RUN"
    '''
  }
}

def void osxSmoke(
  String macosx_deployment_target,
  String compiler,
  MinicondaEnv menv
) {
  def shName = "${pwd()}/scripts/smoke.sh"

  // smoke state is left at the end of the build for possible debugging but
  // each test needs to be run in a clean env.
  dir('smoke') {
    deleteDir()
  }

  prepareSmoke(
    params.PRODUCT,
    params.EUPS_TAG,
    shName,
    "${pwd()}/distrib",
    compiler,
    macosx_deployment_target,
    menv,
    "${pwd()}/buildbot-scripts"
  )

  dir('buildbot-scripts') {
    git([
      url: 'https://github.com/lsst-sqre/buildbot-scripts.git',
      branch: 'master'
    ])
  }

  dir('smoke') {
    withEnv([
      "RUN_DEMO=${params.RUN_DEMO}",
    ]) {
      shColor """
        set -e

        ${shName}
      """
    }
  }
}

def void prepareBuild(
  String product,
  String eupsTag,
  String shName,
  String distribDir,
  String compiler,
  String macosx_deployment_target,
  MinicondaEnv menv
) {
  def script = buildScript(
    product,
    eupsTag,
    distribDir,
    compiler,
    macosx_deployment_target,
    menv
  )

  writeFile(file: shName, text: script)
  shColor "chmod a+x ${shName}"
}

def void prepareSmoke(
  String product,
  String eupsTag,
  String shName,
  String distribDir,
  String compiler,
  String macosx_deployment_target,
  MinicondaEnv menv,
  String ciScriptsPath
) {
  def script = smokeScript(
    product,
    eupsTag,
    distribDir,
    compiler,
    macosx_deployment_target,
    menv,
    ciScriptsPath
  )

  writeFile(file: shName, text: script)
  shColor "chmod a+x ${shName}"
}

def void s3Push(String ... parts) {
  def path = joinPath(parts)

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
      aws s3 sync --only-show-errors ./distrib/ s3://\$EUPS_S3_BUCKET/stack/${path}
    """
  }
}

def void cleanup() {
  shColor 'rm -rf "./build/.lockDir"'
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
def String buildScript(
  String products,
  String tag,
  String eupsPkgroot,
  String compiler,
  String macosx_deployment_target,
  MinicondaEnv menv
) {
  scriptPreamble(compiler, macosx_deployment_target, menv) +
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
def String smokeScript(
  String products,
  String tag,
  String eupsPkgroot,
  String compiler,
  String macosx_deployment_target,
  MinicondaEnv menv,
  String ciScriptsPath
) {
  scriptPreamble(compiler, macosx_deployment_target, menv) +
  dedent("""
    export EUPS_PKGROOT="${eupsPkgroot}"

    curl -sSL ${newinstall_url} | bash -s -- -cb
    . ./loadLSST.bash

    # override newinstall.sh configured EUPS_PKGROOT
    export EUPS_PKGROOT="${eupsPkgroot}"

    eups distrib install ${products} -t "${tag}" -vvv

    if [[ \$RUN_DEMO == true ]]; then
      ${ciScriptsPath}/runManifestDemo.sh --tag "${tag}" --small
    fi
  """)
}

@NonCPS
def String scriptPreamble(
  String compiler,
  String macosx_deployment_target='10.9',
  MinicondaEnv menv
) {
  dedent("""
    set -e
    set -x

    if [[ -n \$CMIRROR_S3_BUCKET ]]; then
        export CONDA_CHANNELS="http://\${CMIRROR_S3_BUCKET}/pkgs/free"
        export MINICONDA_BASE_URL="http://\${CMIRROR_S3_BUCKET}/miniconda"
    fi

    if [[ -n \$EUPS_S3_BUCKET ]]; then
        export EUPS_PKGROOT_BASE_URL="https://\${EUPS_S3_BUCKET}/stack"
    fi

    # isolate eups cache files
    export EUPS_USERDATA="\${PWD}/.eups"

    # isolate conda config
    export CONDARC="\${PWD}/.condarc"

    if [[ \$(uname -s) == Darwin* ]]; then
      export MACOSX_DEPLOYMENT_TARGET="${macosx_deployment_target}"
    fi

    export LSST_PYTHON_VERSION="${menv.pythonVersion}"
    export MINICONDA_VERSION="${menv.minicondaVersion}"
    export LSSTSW_REF="${menv.lsstswRef}"
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

class MinicondaEnv implements Serializable {
  String pythonVersion
  String minicondaVersion
  String lsstswRef

  // unfortunately, a constructor is required under the security sandbox
  // See: https://issues.jenkins-ci.org/browse/JENKINS-34741
  MinicondaEnv(String p, String m, String l) {
    this.pythonVersion = p
    this.minicondaVersion = m
    this.lsstswRef = l
  }

  String slug() {
    "miniconda${pythonVersion}-${minicondaVersion}-${lsstswRef}"
  }
}

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

def String wrapContainer(String imageName, String tag) {
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
