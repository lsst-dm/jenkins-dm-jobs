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
    config = util.scipipeConfig()
    sqre = util.sqreConfig()
  }
}

notify.wrap {
  util.requireParams([
    'COMPILER',
    'EUPS_TAG',
    'IMAGE',
    'LABEL',
    'LSSTSW_REF',
    'MINIVER',
    'PRODUCT',
    'PUBLISH',
    'PYTHON_VERSION',
    'RUN_DEMO',
    'RUN_SCONS_CHECK',
    'SMOKE',
    'TIMEOUT',
    'WIPEOUT',
  ])

  String eupsTag        = params.EUPS_TAG
  String image          = util.emptyToNull(params.IMAGE) // '' means null
  String label          = params.LABEL
  String lsstswRef      = params.LSSTSW_REF
  String miniver        = params.MINIVER
  String product        = params.PRODUCT
  Boolean publish       = params.PUBLISH
  String pythonVersion  = params.PYTHON_VERSION
  Boolean runDemo       = params.RUN_DEMO
  Boolean runSconsCheck = params.RUN_SCONS_CHECK
  Boolean smoke         = params.SMOKE
  Integer timeout       = params.TIMEOUT
  Boolean wipeout       = params.WIPEOUT

  def py = new MinicondaEnv(pythonVersion,miniver, lsstswRef)

  def buildTarget = [
    product: product,
    eups_tag: eupsTag,
  ]

  def smokeConfig = null
  if (smoke) {
    smokeConfig = [
      run_demo: runDemo,
      run_scons_check: runSconsCheck,
    ]
  }

  // XXX this lookup table should be moved to a config file instead of being
  // hardcoded
  switch(label) {
    case 'centos-7':
      linuxTarballs(image, 'el7', compiler, py,
        timeout, buildTarget, smokeConfig, wipeout, publish)
      break
    case 'centos-6':
      linuxTarballs(image, 'el6', compiler, py,
        timeout, buildTarget, smokeConfig, wipeout, publish)
      break
    case 'osx-10.11':
      osxTarballs(label, '10.9', compiler, py,
        timeout, buildTarget, smokeConfig, wipeout, publish)
      break
    default:
      error "unsupported platform: ${label}"
  }
} // notify.wrap

/**
 * Build EUPS tarballs inside of a docker container.
 *
 * @param imageName docker image slug
 * @param platform Eg., 'el7'
 * @param compiler Eg., 'system-gcc'
 * @param menv Miniconda object
 * @param timelimit Integer build timeout in hours
 * @param buildTarget Map
 * @param buildTarget.product String
 * @param buildTarget.eups_tag String
 * @param smoke Map `null` disables running a smoke test
 * @param smoke.run_demo Boolean
 * @param smoke.run_scons_check Boolean
 * @param wipeout Boolean
 * @param publish Boolean
 */
def void linuxTarballs(
  String imageName,
  String platform,
  String compiler,
  MinicondaEnv menv,
  Integer timelimit,
  Map buildTarget,
  Map smokeConfig,
  Boolean wipeout = false,
  Boolean publish = false
) {
  def String slug = menv.slug()
  def envId = util.joinPath('redhat', platform, compiler, slug)

  def run = {
    if (wipeout) {
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
        stage("build ${envId}") {
          docker.image(imageName).pull()
          linuxBuild(imageName, compiler, menv, buildTarget)
        }
        stage('smoke') {
          if (smokeConfig) {
            linuxSmoke(imageName, compiler, menv, buildTarget, smokeConfig)
          }
        }

        stage('publish') {
          if (publish) {
            s3PushDocker(envId)
          }
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
 * @param buildTarget Map
 * @param buildTarget.product String
 * @param buildTarget.eups_tag String
 * @param smoke Map `null` disables running a smoke test
 * @param smoke.run_demo Boolean
 * @param smoke.run_scons_check Boolean
 * @param wipeout Boolean
 * @param publish Boolean
 */
def void osxTarballs(
  String label,
  String macosx_deployment_target,
  String compiler,
  MinicondaEnv menv,
  Integer timelimit,
  Map smokeConfig,
  Boolean wipeout = false,
  Boolean publish = false
) {
  def String slug = menv.slug()
  def envId = util.joinPath('osx', macosx_deployment_target, compiler, slug)

  def run = {
    if (wipeout) {
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
        stage('build') {
          osxBuild(macosx_deployment_target, compiler, menv, buildTarget)
        }

        stage('smoke') {
          if (smokeConfig) {
            osxSmoke(
              macosx_deployment_target,
              compiler,
              menv,
              buildTarget,
              smokeConfig
            )
          }
        } //stage

        stage('publish') {
          if (publish) {
            s3PushVenv(envId)
          }
        }
      } // dir
    } // withCredentials
  } // run

  node(label) {
    timeout(time: timelimit, unit: 'HOURS') {
      run()
    }
  }
}

/**
 * Run Linux specific tarball build.
 *
 * @param imageName docker image slug
 * @param compiler Eg., 'system-gcc'
 * @param menv Miniconda object
 * @param buildTarget Map
 * @param buildTarget.product String
 * @param buildTarget.eups_tag String
 */
def void linuxBuild(
  String imageName,
  String compiler,
  MinicondaEnv menv,
  Map buildTarget
) {
  def cwd      = pwd()
  def buildDir = "${cwd}/build"
  def distDir  = "${cwd}/distrib"
  def shDir    = "${buildDir}/scripts"
  def ciDir    = "${cwd}/ci-scripts"

  def buildDirContainer = '/build'
  def distDirContainer  = '/distrib'
  def ciDirContainer    = '/ci-scripts'

  def shBasename = 'run.sh'
  def shName = "${shDir}/${shBasename}"
  def localImageName = "${imageName}-local"

  try {
    util.createDirs([
      buildDir,
      distDir,
      shDir,
    ])

    // sanitize build dir to ensure log collection is for the current build
    // only
    emptyExistingDir(eupsBuildDir(buildDir, menv))

    prepareBuild(
      buildTarget.product,
      buildTarget.eups_tag,
      shName,
      distDirContainer,
      compiler,
      null,
      menv,
      ciDirContainer
    )

    dir(ciDir) {
      util.cloneCiScripts()
    }

    util.wrapContainer(imageName, localImageName)

    withEnv([
      "RUN=/build/scripts/${shBasename}",
      "IMAGE=${localImageName}",
      "BUILDDIR=${buildDir}",
      "BUILDDIR_CONTAINER=${buildDirContainer}",
      "DISTDIR=${distDir}",
      "DISTDIR_CONTAINER=${distDirContainer}",
      "CIDIR=${ciDir}",
      "CIDIR_CONTAINER=${ciDirContainer}",
    ]) {
      // XXX refactor to use util.insideWrap
      util.bash '''
        docker run \
          -v "${BUILDDIR}:${BUILDDIR_CONTAINER}" \
          -v "${DISTDIR}:${DISTDIR_CONTAINER}" \
          -v "${CIDIR}:${CIDIR_CONTAINER}" \
          -w /build \
          -e CMIRROR_S3_BUCKET="$CMIRROR_S3_BUCKET" \
          -e EUPS_S3_BUCKET="$EUPS_S3_BUCKET" \
          -u "$(id -u -n)" \
          "$IMAGE" \
          bash -c "$RUN"
      '''
    } // withEnv
  } finally {
    record(buildDir, menv)
    cleanup(buildDir)
  }
} // linuxBuild

/**
 * Run OSX specific tarball build.
 *
 * @param macosx_deployment_target Eg., '10.9'
 * @param compiler Eg., 'system-gcc'
 * @param menv Miniconda object
 * @param buildTarget Map
 * @param buildTarget.product String
 * @param buildTarget.eups_tag String
 */
def void osxBuild(
  String macosx_deployment_target,
  String compiler,
  MinicondaEnv menv,
  Map buildTarget
) {
  def cwd      = pwd()
  def buildDir = "${cwd}/build"
  def distDir  = "${cwd}/distrib"
  def shDir    = "${buildDir}/scripts"
  def ciDir    = "${cwd}/ci-scripts"

  def shName = "${shDir}/run.sh"

  try {
    util.createDirs([
      buildDir,
      distDir,
      shDir,
    ])

    // sanitize build dir to ensure log collection is for the current build
    // only
    emptyExistingDir(eupsBuildDir(buildDir, menv))

    prepareBuild(
      buildTarget.product,
      buildTarget.eups_tag,
      "${shName}",
      distDir,
      compiler,
      macosx_deployment_target,
      menv,
      ciDir
    )

    dir(ciDir) {
      util.cloneCiScripts()
    }

    dir(buildDir) {
      util.bash shName
    }
  } finally {
    record(buildDir, menv)
    cleanup(buildDir)
  }
} // osxBuild

/**
 * Run Linux specific tarball smoke test(s).
 *
 * @param imageName docker image slug
 * @param compiler Eg., 'system-gcc'
 * @param menv Miniconda object
 * @param buildTarget Map
 * @param buildTarget.product String
 * @param buildTarget.eups_tag String
 * @param smoke Map
 * @param smoke.run_demo Boolean
 * @param smoke.run_scons_check Boolean
 */
def void linuxSmoke(
  String imageName,
  String compiler,
  MinicondaEnv menv,
  Map buildTarget,
  Map smokeConfig
) {
  def cwd      = pwd()
  def smokeDir = "${cwd}/smoke"
  def distDir  = "${cwd}/distrib"
  def shDir    = "${smokeDir}/scripts"
  def ciDir    = "${cwd}/ci-scripts"

  def smokeDirContainer = '/smoke'
  def distDirContainer  = '/distrib'
  def ciDirContainer    = '/ci-scripts'

  def shBasename = 'run.sh'
  def shName = "${shDir}/${shBasename}"
  def localImageName = "${imageName}-local"

  try {
    // smoke state is left at the end of the build for possible debugging but
    // each test needs to be run in a clean env.
    util.emptyDirs([smokeDir])

    prepareSmoke(
      buildTarget.product,
      buildTarget.eups_tag,
      shName,
      distDirContainer,
      compiler,
      null,
      menv,
      ciDirContainer
    )

    dir(ciDir) {
      util.cloneCiScripts()
    }

    util.wrapContainer(imageName, localImageName)

    withEnv([
      "RUN=/smoke/scripts/${shBasename}",
      "IMAGE=${localImageName}",
      "RUN_DEMO=${smokeConfig.run_demo}",
      "RUN_SCONS_CHECK=${smokeConfig.run_scons_check}",
      "SMOKEDIR=${smokeDir}",
      "SMOKEDIR_CONTAINER=${smokeDirContainer}",
      "DISTDIR=${distDir}",
      "DISTDIR_CONTAINER=${distDirContainer}",
      "CIDIR=${ciDir}",
      "CIDIR_CONTAINER=${ciDirContainer}",
    ]) {
      // XXX refactor to use util.insideWrap
      util.bash '''
        docker run \
          -v "${SMOKEDIR}:${SMOKEDIR_CONTAINER}" \
          -v "${DISTDIR}:${DISTDIR_CONTAINER}" \
          -v "${CIDIR}:${CIDIR_CONTAINER}" \
          -w /smoke \
          -e CMIRROR_S3_BUCKET="$CMIRROR_S3_BUCKET" \
          -e EUPS_S3_BUCKET="$EUPS_S3_BUCKET" \
          -e RUN_DEMO="$RUN_DEMO" \
          -e RUN_SCONS_CHECK="$RUN_SCONS_CHECK" \
          -e FIX_SHEBANGS=true \
          -u "$(id -u -n)" \
          "$IMAGE" \
          bash -c "$RUN"
      '''
    } // withEnv
  } finally {
    record(smokeDir, menv)
  }
} // linuxSmoke

/**
 * Generate + write build script.
 *
 * @param macosx_deployment_target Eg., '10.9'
 * @param compiler Eg., 'system-gcc'
 * @param menv Miniconda object
 * @param buildTarget Map
 * @param buildTarget.product String
 * @param buildTarget.eups_tag String
 * @param smoke Map
 * @param smoke.run_demo Boolean
 * @param smoke.run_scons_check Boolean
 */
def void osxSmoke(
  String macosx_deployment_target,
  String compiler,
  MinicondaEnv menv,
  Map bulidTarget,
  Map smokeConfig
) {
  def cwd      = pwd()
  def smokeDir = "${cwd}/smoke"
  def shName   = "${cwd}/scripts/smoke.sh"
  def ciDir    = "${cwd}/ci-scripts"

  try {
    // smoke state is left at the end of the build for possible debugging but
    // each test needs to be run in a clean env.
    dir(smokeDir) {
      deleteDir()
    }

    prepareSmoke(
      buildTarget.product,
      buildTarget.eups_tag,
      shName,
      "${cwd}/distrib",
      compiler,
      macosx_deployment_target,
      menv,
      ciDir
    )

    dir(ciDir) {
      util.cloneCiScripts()
    }

    dir(smokeDir) {
      withEnv([
        "RUN_DEMO=${smokeConfig.run_demo}",
        "RUN_SCONS_CHECK=${smokeConfig.run_scons_check}",
        "FIX_SHEBANGS=true",
      ]) {
        util.bash shName
      }
    }
  } finally {
    record(smokeDir, menv)
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
  MinicondaEnv menv,
  String ciDir
) {
  def script = buildScript(
    product,
    eupsTag,
    distribDir,
    compiler,
    macosx_deployment_target,
    menv,
    ciDir
  )

  writeFile(file: shName, text: script)
  util.bash "chmod a+x ${shName}"
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
  String ciDir
) {
  def script = smokeScript(
    product,
    eupsTag,
    distribDir,
    compiler,
    macosx_deployment_target,
    menv,
    ciDir
  )

  writeFile(file: shName, text: script)
  util.bash "chmod a+x ${shName}"
}

/**
 * Push {@code ./distrib} dir to an s3 bucket under the "path" formed by
 * joining the {@code parts} parameters.
 */
def void s3PushVenv(String ... parts) {
  def objectPrefix = "stack/" + util.joinPath(parts)
  def cwd = pwd()

  util.bash """
    # do not assume virtualenv is present
    pip install virtualenv
    virtualenv venv
    . venv/bin/activate
    pip install --upgrade pip
    pip install --upgrade awscli==${sqre.awscli.pypi.version}
  """

  def env = [
    "EUPS_PKGROOT=${cwd}/distrib",
    "EUPS_S3_OBJECT_PREFIX=${objectPrefix}",
  ]

  withEnv(env) {
    withEupsBucketEnv {
      util.bash """
        . venv/bin/activate
        ${s3PushCmd()}
      """
    } // withEupsBucketEnv
  } // withEnv
}

/**
 * Push {@code ./distrib} dir to an s3 bucket under the "path" formed by
 * joining the {@code parts} parameters.
 */
def void s3PushDocker(String ... parts) {
  def objectPrefix = "stack/" + util.joinPath(parts)
  def cwd = pwd()

  def env = [
    "EUPS_PKGROOT=${cwd}/distrib",
    "EUPS_S3_OBJECT_PREFIX=${objectPrefix}",
    "HOME=${cwd}/home",
  ]

  withEnv(env) {
    withEupsBucketEnv {
      docker.image(util.defaultAwscliImage()).inside {
        // alpine does not include bash by default
        util.posixSh(s3PushCmd())
      } // .inside
    } //withEupsBucketEnv
  } // withEnv
}

/**
 * Returns a shell command string for pushing the EUPS_PKGROOT to s3.
 *
 * @return String cmd
 */
def String s3PushCmd() {
  // do not interpolate now -- all values should come from the shell env.
  return util.dedent('''
    aws s3 cp \
      --only-show-errors \
      --recursive \
      "${EUPS_PKGROOT}/" \
      "s3://${EUPS_S3_BUCKET}/${EUPS_S3_OBJECT_PREFIX}"
  ''')
}

/**
 * Declares the following env vars from credentials:
 * - AWS_ACCESS_KEY_ID
 * - AWS_SECRET_ACCESS_KEY
 * - EUPS_S3_BUCKET
 */
def void withEupsBucketEnv(Closure run) {
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
    run()
  }
}

/**
 *  Record logs
 */
def void record(String buildDir, MinicondaEnv menv) {
  def eupsBuildDir = eupsBuildDir(buildDir, menv)

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
  MinicondaEnv menv,
  String ciDir
) {
  scriptPreamble(
    compiler,
    macosx_deployment_target,
    menv,
    true,
    ciDir
  ) +
  util.dedent("""
    curl -sSL ${util.newinstallUrl()} | bash -s -- -cb
    . ./loadLSST.bash

    for prod in ${products}; do
      eups distrib install "\$prod" -t "${tag}" -vvv
    done

    export EUPS_PKGROOT="${eupsPkgroot}"

    # remove any pre-existing eups tags to prevent them from being
    # [re]published
    # the tarball pkgroots have tag files (.list) directly in the root of the
    # repo
    if [[ -e \$EUPS_PKGROOT ]]; then
      rm -f "\${EUPS_PKGROOT}/*.list"
    fi

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
  String ciDir
) {
  def baseUrl = util.githubSlugToUrl('lsst/base')

  scriptPreamble(
    compiler,
    macosx_deployment_target,
    menv,
    true,
    ciDir
  ) +
   util.dedent("""
    export EUPS_PKGROOT="${eupsPkgroot}"
    export BASE_URL="${baseUrl}"

    curl -sSL ${util.newinstallUrl()} | bash -s -- -cb
    . ./loadLSST.bash

    # override newinstall.sh configured EUPS_PKGROOT
    export EUPS_PKGROOT="${eupsPkgroot}"

    for prod in ${products}; do
      eups distrib install "\$prod" -t "${tag}" -vvv
    done

    if [[ \$FIX_SHEBANGS == true ]]; then
      curl -sSL ${config.shebangtron.url} | python
    fi

    if [[ \$RUN_DEMO == true ]]; then
      ${ciDir}/runManifestDemo.sh --tag "${tag}" --small
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
      git clone "$BASE_URL"
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
  boolean useTarballs,
  String ciDir
) {
  util.dedent("""
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

    source "${ciDir}/ccutils.sh"
    cc::setup_first "${compiler}"
    """
  )
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

/**
 * Empty dir only if it exists.  This is intended to avoid the side effect of
 * the dir() step of creating an empty dir if it does not already exists.
 *
 * @param path String path to dir to empty, if it exists
 */
def void emptyExistingDir(String path) {
  if (fileExists(path)) {
    dir(path) {
      deleteDir()
    }
  }
}

/**
 * Calculate EupsBuildDir path
 *
 * @param buildDir String root path to newinstall.sh env
 * @param menv MinicondaEnv
 * @return String path to EupsBuildDir
*/
@NonCPS
def String eupsBuildDir(String buildDir, MinicondaEnv menv) {
  return "${buildDir}/stack/${menv.slug()}/EupsBuildDir"
}
