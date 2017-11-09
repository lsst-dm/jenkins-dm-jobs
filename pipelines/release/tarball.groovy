import groovy.transform.Field

class UnsupportedCompiler extends Exception {}

def notify = null
node('jenkins-master') {
  if (params.WIPEOUT) {
    deleteDir()
  }

  dir('jenkins-dm-jobs') {
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs(),
      changelog: false,
      poll: false
    ])
    notify = load 'pipelines/lib/notify.groovy'
    util = load 'pipelines/lib/util.groovy'
  }
}

@Field String newinstall_url = 'https://raw.githubusercontent.com/lsst/lsst/master/scripts/newinstall.sh'
@Field String shebangtron_url = 'https://raw.githubusercontent.com/lsst/shebangtron/master/shebangtron'

try {
  notify.started()

  def requiredParams = [
    'PRODUCT',
    'EUPS_TAG',
    'PYVER',
    'MINIVER',
    'LSSTSW_REF',
    'OS',
    'TIMEOUT',
  ]

  requiredParams.each { it ->
    if (!params.get(it)) {
      error "${it} parameter is required"
    }
  }

  def py = new MinicondaEnv(params.PYVER, params.MINIVER, params.LSSTSW_REF)
  def timelimit = params.TIMEOUT.toInteger()

  stage("${params.OS}.${py.slug()}") {
    switch(params.OS) {
      case 'centos-7':
        def imageName = 'lsstsqre/centos:7-newinstall'
        linuxTarballs(imageName, 'el7', 'gcc-system', py, timelimit)
        break
      case 'centos-6':
        def imageName = 'lsstsqre/centos:6-newinstall'
        linuxTarballs(imageName, 'el6', 'devtoolset-3', py, timelimit)
        break
      case 'osx-10.11':
        osxTarballs('10.11', '10.9', 'clang-800.0.42.1', py, timelimit)
        break
      default:
        error "unsupported platform: ${params.PLATFORM}"
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

/**
 * Build EUPS tarballs inside of a docker container.
 *
 * @param imageName docker image slug
 * @param platform Eg., 'el7'
 * @param compiler Eg., 'system-gcc'
 * @param menv Miniconda object
 * @param timelimit Integer build timeout in hours
 */
def void linuxTarballs(
  String imageName,
  String platform,
  String compiler,
  MinicondaEnv menv,
  Integer timelimit
) {
  def String slug = menv.slug()
  def envId = util.joinPath('redhat', platform, compiler, slug)

  def run = {
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
  } // run()

  node('docker') {
    timeout(time: timelimit, unit: 'HOURS') {
      run()
    }
  }
}

/**
 * Build EUPS tarballs in a regular directory.
 *
 * @param imageName docker image slug
 * @param platform build platform Eg., '10.11'
 * @param macosx_deployment_target Eg., '10.9'
 * @param compiler Eg., 'system-gcc'
 * @param menv Miniconda object
 * @param timelmit Integer build timeout in hours
 */
def void osxTarballs(
  String platform,
  String macosx_deployment_target,
  String compiler,
  MinicondaEnv menv,
  Integer timelimit
) {
  def String slug = menv.slug()
  def envId = util.joinPath('osx', macosx_deployment_target, compiler, slug)

  def run = {
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
  } // run()

  node("osx-${platform}") {
    timeout(time: timelimit, unit: 'HOURS') {
      run()
    }
  }
}

/**
 * Run Linux specific tarball build.
 */
def void linuxBuild(String imageName, String compiler, MinicondaEnv menv) {
  def buildDir = "${pwd()}/build"
  def distDir  = "${pwd()}/distrib"
  def shDir    = "${buildDir}/scripts"

  def shBasename = 'run.sh'
  def shName = "${shDir}/${shBasename}"
  def localImageName = "${imageName}-local"

  try {
    [
      buildDir,
      distDir,
      shDir,
    ].each { d ->
      dir(d) {
        writeFile(file: '.dummy', text: '')
      }
    }

    // sanitize build dir to ensure log collection is for the current build
    // only
    dir("${buildDir}/stack/current/EupsBuildDir") {
      deleteDir()
    }

    prepareBuild(
      params.PRODUCT,
      params.EUPS_TAG,
      shName,
      '/distrib', // path inside container
      compiler,
      null,
      menv
    )

    util.wrapContainer(imageName, localImageName)

    withEnv([
      "RUN=/build/scripts/${shBasename}",
      "IMAGE=${localImageName}",
      "BUILDDIR=${buildDir}",
      "DISTDIR=${distDir}",
    ]) {
      util.shColor '''
        set -e

        docker run \
          --storage-opt size=100G \
          -v "${BUILDDIR}:/build" \
          -v "${DISTDIR}:/distrib" \
          -w /build \
          -e CMIRROR_S3_BUCKET="$CMIRROR_S3_BUCKET" \
          -e EUPS_S3_BUCKET="$EUPS_S3_BUCKET" \
          -u "$(id -u -n)" \
          "$IMAGE" \
          sh -c "$RUN"
      '''
    } // withEnv
  } finally {
    record(buildDir)
    cleanup(buildDir)
  }
} // linuxBuild

/**
 * Run OSX specific tarball build.
 */
def void osxBuild(
  String macosx_deployment_target,
  String compiler,
  MinicondaEnv menv
) {
  def buildDir = "${pwd()}/build"
  def distDir  = "${pwd()}/distrib"
  def shDir    = "${buildDir}/scripts"

  def shName = "${shDir}/run.sh"

  try {
    [
      buildDir,
      distDir,
      shDir,
    ].each { d ->
      dir(d) {
        writeFile(file: '.dummy', text: '')
      }
    }

    // sanitize build dir to ensure log collection is for the current build
    // only
    dir("${buildDir}/stack/current/EupsBuildDir") {
      deleteDir()
    }

    prepareBuild(
      params.PRODUCT,
      params.EUPS_TAG,
      "${shName}",
      "${pwd()}/distrib",
      compiler,
      macosx_deployment_target,
      menv
    )

    dir(buildDir) {
      util.shColor """
        set -e

        "${shName}"
      """
    }
  } finally {
    record(buildDir)
    cleanup(buildDir)
  }
} // osxBuild

/**
 * Run Linux specific tarball smoke test(s).
 */
def void linuxSmoke(String imageName, String compiler, MinicondaEnv menv) {
  def smokeDir = "${pwd()}/smoke"
  def distDir = "${pwd()}/distrib"
  def shDir = "${smokeDir}/scripts"
  def ciDir = "${pwd()}/buildbot-scripts"

  def shBasename = 'run.sh'
  def shName = "${shDir}/${shBasename}"
  def localImageName = "${imageName}-local"

  try {
    // smoke state is left at the end of the build for possible debugging but
    // each test needs to be run in a clean env.
    dir(smokeDir) {
      deleteDir()
      writeFile(file: '.dummy', text: '')
    }

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

    dir(ciDir) {
      git([
        url: 'https://github.com/lsst-sqre/buildbot-scripts.git',
        branch: 'master'
      ])
    }

    util.wrapContainer(imageName, localImageName)

    withEnv([
      "RUN=/smoke/scripts/${shBasename}",
      "IMAGE=${localImageName}",
      "RUN_DEMO=${params.RUN_DEMO}",
      "RUN_SCONS_CHECK=${params.RUN_SCONS_CHECK}",
      "SMOKEDIR=${smokeDir}",
      "DISTDIR=${distDir}",
      "CIDIR=${ciDir}",
    ]) {
      util.shColor '''
        set -e

        docker run \
          --storage-opt size=100G \
          -v "${SMOKEDIR}:/smoke" \
          -v "${DISTDIR}:/distrib" \
          -v "${CIDIR}:/buildbot-scripts" \
          -w /smoke \
          -e CMIRROR_S3_BUCKET="$CMIRROR_S3_BUCKET" \
          -e EUPS_S3_BUCKET="$EUPS_S3_BUCKET" \
          -e RUN_DEMO="$RUN_DEMO" \
          -e RUN_SCONS_CHECK="$RUN_SCONS_CHECK" \
          -e FIX_SHEBANGS=true \
          -u "$(id -u -n)" \
          "$IMAGE" \
          sh -c "$RUN"
      '''
    } // withEnv
  } finally {
    record(smokeDir)
  }
} // linuxSmoke

/**
 * Generate + write build script.
 */
def void osxSmoke(
  String macosx_deployment_target,
  String compiler,
  MinicondaEnv menv
) {
  def smokeDir = "${pwd()}/smoke"
  def shName = "${pwd()}/scripts/smoke.sh"
  def ciDir = "${pwd()}/buildbot-scripts"

  try {
    // smoke state is left at the end of the build for possible debugging but
    // each test needs to be run in a clean env.
    dir(smokeDir) {
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
      ciDir,
    )

    dir(ciDir) {
      git([
        url: 'https://github.com/lsst-sqre/buildbot-scripts.git',
        branch: 'master'
      ])
    }

    dir(smokeDir) {
      withEnv([
        "RUN_DEMO=${params.RUN_DEMO}",
        "RUN_SCONS_CHECK=${params.RUN_SCONS_CHECK}",
        "FIX_SHEBANGS=true",
      ]) {
        util.shColor """
          set -e

          ${shName}
        """
      }
    }
  } finally {
    record(smokeDir)
  }
} // osxSmoke

/**
 * Generate + write build script.
 */
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
  util.shColor "chmod a+x ${shName}"
}

/**
 * Generate + write smoke test script.
 */
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
  util.shColor "chmod a+x ${shName}"
}

/**
 * Push {@code ./distrib} dir to an s3 bucket under the "path" formed by
 * joining the {@code parts} parameters.
 */
def void s3Push(String ... parts) {
  def path = util.joinPath(parts)

  util.shColor '''
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
    util.shColor """
      set -e
      . venv/bin/activate
      aws s3 sync --only-show-errors ./distrib/ s3://\$EUPS_S3_BUCKET/stack/${path}
    """
  }
}

/**
 *  Record logs
 */
def void record(String buildDir) {
  def eupsBuildDir = "${buildDir}/stack/current/EupsBuildDir"

  def archive = [
    '**/*.log',
    '**/*.failed',
  ]

  def reports = [
    '**/pytest-*.xml',
  ]

  dir(eupsBuildDir) {
    archiveArtifacts([
      artifacts: archive.join(', '),
      allowEmptyArchive: true,
      fingerprint: true
    ])

    junit([
      testResults: reports.join(', '),
      allowEmptyResults: true,
    ])
  }
}

/**
 * Cleanup after a build attempt.
 */
def void cleanup(String buildDir) {
  dir("${buildDir}/.lockDir") {
    deleteDir()
  }
}

/**
 * Generate shellscript to build EUPS distrib tarballs.
 */
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
  scriptPreamble(compiler, macosx_deployment_target, menv, true) +
  util.dedent("""
    curl -sSL ${newinstall_url} | bash -s -- -cb
    . ./loadLSST.bash

    for prod in ${products}; do
      eups distrib install --no-server-tags "\$prod" -t "${tag}" -vvv
    done

    export EUPS_PKGROOT="${eupsPkgroot}"
    for prod in ${products}; do
      eups distrib create --server-dir "\$EUPS_PKGROOT" -d tarball "\$prod" -t "${tag}" -vvv
    done
    eups distrib declare --server-dir "\$EUPS_PKGROOT" -t "${tag}" -vvv
  """)
}

/**
 * Generate shellscript to execute a "smoke" install test.
 */
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
  scriptPreamble(compiler, macosx_deployment_target, menv, true) +
  util.dedent("""
    export EUPS_PKGROOT="${eupsPkgroot}"

    curl -sSL ${newinstall_url} | bash -s -- -cb
    . ./loadLSST.bash

    # override newinstall.sh configured EUPS_PKGROOT
    export EUPS_PKGROOT="${eupsPkgroot}"

    for prod in ${products}; do
      eups distrib install --no-server-tags "\$prod" -t "${tag}" -vvv
    done

    if [[ \$FIX_SHEBANGS == true ]]; then
      curl -sSL ${shebangtron_url} | python
    fi

    if [[ \$RUN_DEMO == true ]]; then
      ${ciScriptsPath}/runManifestDemo.sh --tag "${tag}" --small
    fi
  """ + '''
    #
    # use the same version of base that was just installed to rule out source
    # compatibility issues.
    #
    # match:
    # - 13 as 13
    # - 13.0 as 13.0
    # - 13.0+1 as 13.0
    # - 2.9.1.lsst1+1 as 2.9.1.lsst1
    # - 13.0-10-g692d0a9 as 692d0a9
    # - 13.0-10-g692d0a9+1 as 692d0a9
    # - master-gd7f6e4dbf2+24 as d7f6e4dbf2
    # - 3.11.lsst1-2-g6ae2b7a as 6ae2b7a
    #
    # Eg.
    #    13.0-10-692d0a9 d_2017_09_14 ... current d_2017_09_13
    #
    # note that py2.7 compat is required -- the lambda can be dropped under
    # py3.5+
    estring2ref() {
      python -c "
import sys,re;
for line in sys.stdin:
  foo = re.sub(r'^\\s*(?:[\\w.-]+g([a-zA-Z0-9]+)|([\\w.-]+))(?:\\+[\\d]+)?\\s+.*', lambda m: m.group(1) or m.group(2), line)
  if foo is line:
    sys.exit(1)
  print(foo)
"
    }

    if [[ \$RUN_SCONS_CHECK == true ]]; then
      BASE_REF=$(eups list base | estring2ref)

      # sadly, git will not clone by sha1 -- only branch/tag names are allowed
      git clone https://github.com/lsst/base.git
      cd base
      git checkout "$BASE_REF"
      setup -k -r .
      scons
    fi
  ''')
}

/**
 * Generate common shellscript boilerplate.
 */
@NonCPS
def String scriptPreamble(
  String compiler,
  String macosx_deployment_target='10.9',
  MinicondaEnv menv,
  boolean useTarballs
) {
  util.dedent("""
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
    export EUPS_USE_TARBALLS="${useTarballs}"
    """
    + scriptCompiler(compiler)
  )
}

/**
 * Generate shellscript to configure a C/C++ compiler.
 *
 * @param compiler Single String description of compiler.
 * @return String shellscript
 */
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

  util.dedent(setup)
}

/**
 * Represents a miniconda build environment.
 */
class MinicondaEnv implements Serializable {
  String pythonVersion
  String minicondaVersion
  String lsstswRef

  /**
   * Constructor.
   *
   * @param p Python major version number. Eg., '3'
   * @param m Miniconda version string. Eg., '4.2.12'
   * @param l {@code lsst/lsstsw} git ref.
   * @return MinicondaEnv
   */
  // unfortunately, a constructor is required under the security sandbox
  // See: https://issues.jenkins-ci.org/browse/JENKINS-34741
  MinicondaEnv(String p, String m, String l) {
    this.pythonVersion = p
    this.minicondaVersion = m
    this.lsstswRef = l
  }

  /**
   * Generate a single string description of miniconda env.
   */
  String slug() {
    "miniconda${pythonVersion}-${minicondaVersion}-${lsstswRef}"
  }
}
