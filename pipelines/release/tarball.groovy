node('jenkins-master') {
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
    'OSFAMILY',
    'PLATFORM',
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
  String osfamily       = params.OSFAMILY
  String platform       = params.PLATFORM
  Boolean publish       = params.PUBLISH
  String pythonVersion  = params.PYTHON_VERSION
  Boolean runDemo       = params.RUN_DEMO
  Boolean runSconsCheck = params.RUN_SCONS_CHECK
  Boolean smoke         = params.SMOKE
  Integer timelimit     = params.TIMEOUT
  Boolean wipeout       = params.WIPEOUT

  // buildConfig
  def bc = [
    eupsTag: eupsTag,
    image: image,
    label: label,
    lsstswRef: lsstswRef,
    miniver: miniver,
    product: product,
    osfamily: osfamily,
    platform: platform,
    publish: publish,
    pythonVersion: pythonVersion,
    runDemo: runDemo,
    runSconsCheck: runSconsCheck,
    smoke: smoke,
    timelimit: timelimit,
    wipeout: wipeout,
    menv: new MinicondaEnv(pythonVersion, miniver, lsstswRef),
  ]

  bc.pkgrootSlug = pkgrootSlug(bc)

  // if a docker image is specified, run on a 'docker' agent
  if (bc.image) {
    bc.nodeLabel = 'docker'
  } else {
    bc.nodeLabel = bc.label
  }

  if (bc.osfamily == 'osx') {
    // use the platform string for now -- may need to diverge
    bc.macosxDeploymentTarget = bc.platform
  }

  node(bc.nodeLabel) {
    timeout(time: bc.timelimit, unit: 'HOURS') {
      // layout can only be determined inside a node block
      bc.layout = pathLayout(
        buildConfig: bc,
        rootDir: util.joinPath(pwd(), bc.pkgrootSlug),
      ).asImmutable()

      dir(bc.pkgrootSlug) {
        // build configuration may not be changed past this point
        buildTarballs(
          buildConfig: bc.asImmutable(),
        )
      } // dir
    } // timeout
  } // node
} // notify.wrap


/** Construct tarballs from build + build layout configuration.
 *
 * @param p Map
 * @param p.buildConfig Map
 */
def void buildTarballs(Map p) {
  Map bc = p.buildConfig
  Map l  = bc.layout

  if (bc.wipeout) {
    deleteDir()
  } else {
    // smoke state is left at the end of the build for possible debugging
    // but each test needs to be run in a clean env.
    util.emptyDirs([l.buildHomeDir, l.smokeDir])
  }

  // create all paths
  util.createDirs(l.Dirs)

  // sanitize the eups product build dir to ensure log collection is for the
  // current build only.  This dir should not be created if it doesn't
  // already exist.
  emptyExistingDir(l.eupsBuildDir)

  // checkout ci-scripts outside of docker container as the git step has
  // historically been broken inside containers
  dir(ciDir) {
    util.cloneCiScripts()
  }

  switch(osfamily) {
    case 'redhat':
      linuxTarballs(buildConfig: bc)
      break
    case 'osx':
      osxTarballs(buildConfig: bc)
      break
    default:
      error "unsupported platform: ${label}"
  }
}

/**
 * Build EUPS tarballs inside of a docker container.
 *
 * @param p Map
 * @param p.buildConfig Map
 */
def void linuxTarballs(Map p) {
  Map bc = p.buildConfig

  stage("build ${bc.pkgrootSlug}") {
    util.insideWrap(bc.image) {
      tarballBuild(buildConfig: bc)
    }
  }

  stage('smoke') {
    if (bc.smoke) {
      util.insideWrap(bc.image) {
        tarballSmoke(buildConfig: bc)
      }
    }
  }

  stage('publish') {
    if (bc.publish) {
      s3PushDocker(buildConfig: bc)
    }
  }
}

/**
 * Build EUPS tarballs in a regular directory.
 *
 * @param p Map
 * @param p.buildConfig Map
 */
def void osxTarballs(Map p) {
  Map bc = p.buildConfig

  stage("build ${buildConfig.pkgrootSlug}") {
    tarballBuild(buildConfig: bc)
  }

  stage('smoke') {
    if (bc.smokeConfig) {
      tarballSmoke(buildConfig: bc)
    }
  }

  stage('publish') {
    if (bc.publish) {
      s3PushVenv(buildConfig: bc)
    }
  }
}

/**
 * Build EUPS tarball packages.
 *
 * @param p Map
 * @param p.buildConfig Map
 */
def void tarballBuild(Map p) {
  Map bc = p.buildConfig
  Map l  = bc.layout

  try {
    def script = buildScript(buildConfig: bc)
    writeScript(file: l.buildSh, text: script)

    dir(l.buildDir) {
      withTarballEnv {
        withEnv([
          "HOME=${l.buildHomeDir}",
          "EUPS_USERDATA=${l.buildHomeDir}/.eups_userdata",
          "CONDARC=${l.buildHomeDir}/.condarc",
        ]) {
          util.bash(l.buildSh)
        }
      } // withTarballEnv
    } // dir
  } finally {
    record(l.buildDir)
    cleanup(l.buildDir)
  }
} // tarballBuild

/**
 * Run smoke test(s) on constructed EUPS tarballs.
 *
 */
def void tarballSmoke(Map p) {
  Map bc = p.buildConfig
  Map l  = p.layout

  try {
    def script = smokeScript(buildConfig: bc)
    writeScript(file: l.smokeSh, text: script)

    dir(l.smokeDir) {
      withTarballEnv {
        withEnv([
          "HOME=${l.smokeHomeDir}",
          "EUPS_USERDATA=${l.smokeHomeDir}/.eups_userdata",
          "CONDARC=${l.smokeHomeDir}/.condarc",

          "RUN_DEMO=${bc.run_demo}",
          "RUN_SCONS_CHECK=${bc.run_scons_check}",
          "FIX_SHEBANGS=true",
        ]) {
          util.bash(l.smokeSh)
        }
      } // withTarballEnv
    }
  } finally {
    record(l.smokeDir)
  }
} // tarballSmoke

/**
 * write executable file
 *
 * @param p Map
 * @param p.file String name of script file to write
 * @param p.text String script text
 */
def void writeScript(Map p) {
  String file = p.file
  String text = p.text

  echo "creating script ${file}:"
  echo text

  writeFile(file: file, text: text)
  util.bash "chmod a+x ${p.file}"
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
    withEupsPushEnv {
      util.bash """
        . venv/bin/activate
        ${s3PushCmd()}
      """
    } // withEupsPushEnv
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
  ]

  withEnv(env) {
    withEupsPushEnv {
      docker.image(util.defaultAwscliImage()).inside {
        util.bash(s3PushCmd())
      }
    } //withEupsPushEnv
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
def void withEupsPushEnv(Closure run) {
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
def void record(String rootDir) {
  def archive = [
    '**/*.log',
    '**/*.failed',
  ]

  def reports = [
    '**/pytest-*.xml',
  ]

  dir(rootDir) {
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
 * @param p Map
 * @param p.buildConfig Map
 */
def String buildScript(Map p) {
  Map bc = p.buildConfig
  Map l  = bc.layout

  util.dedent("""
    #!/bin/bash

    set -xe
    # do not allow | to hide curl failures
    set -o pipefail

    source "\${CCUTILS_DIR}/ccutils.sh"
    cc::setup_first "\$LSST_COMPILER"

    curl -sSL ${util.newinstallUrl()} | bash -s -- -cb
    . ./loadLSST.bash

    for prod in ${bc.products}; do
      eups distrib install "\$prod" -t "${tag}" -vvv
    done

    # XXX
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

  util.dedent("""
    #!/bin/bash

    set -xe
    # do not allow | to hide curl failures
    set -o pipefail

    source "\${CCUTILS_DIR}/ccutils.sh"
    cc::setup_first "${compiler}"

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
      curl -sSL ${util.shebangtronUrl()} | python
    fi

    if [[ \$RUN_DEMO == true ]]; then
      ${ciDir}/runManifestDemo.sh --eups-tag "${tag}" --small
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
def String eupsBuildDir(String buildDir, MinicondaEnv menv) {
  return "${buildDir}/stack/${menv.slug()}/EupsBuildDir"
}

/**
 * Relative path to EUPS_PKGROOT / ABI slug
 *
 * @param buildConfig Map "buildConfig"
 * @return String slug
 */
def String pkgrootSlug(Map buildConfig) {
  def path = [
    buildConfig.osfamily,
    buildConfig.platform,
    buildConfig.compiler,
    buildConfig.menv.slug(),
  ]

  return util.joinPath(path)
}

/**
 * Setup CMIRROR_S3_BUCKET and EUPS_S3_BUCKET env vars
 *
 * @param p Map
 * @param p.buildConfig Map
 * @param run Closure
 */
def void withTarballEnv(Map p, Closure invoke) {
  Map bc = p.buildConfig
  Map l  = bc.layout

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
    withEnv([
      "CONDA_CHANNELS=http://${CMIRROR_S3_BUCKET}/pkgs/free",
      "MINICONDA_BASE_URL=http://${CMIRROR_S3_BUCKET}/miniconda",
      "EUPS_PKGROOT_BASE_URL=https://${EUPS_S3_BUCKET}/stack",
      "MACOSX_DEPLOYMENT_TARGET=${bc.macosxDeploymentTarget}",
      "LSST_PYTHON_VERSION=${bc.menv.pythonVersion}",
      "LSST_COMPILER=${bc.compiler}",
      "MINICONDA_VERSION=${bc.menv.minicondaVersion}",
      "LSSTSW_REF=${bc.menv.lsstswRef}",
      "CCUTILS_DIR=${l.ciDir}",
      "EUPS_USE_TARBALLS=true",
    ]) {
     invoke()
    }
  }
} // withTarballEnv

/**
 * Generate directory path layout for build/smoke.
 *
 * @param p Map
 * @param p.rootDir String
 * @return layout Map
 */
def Map pathLayout(Map p) {
  Map bc         = p.buildConfig
  String rootDir = p.rootDir

  def l = [
    distDir:    "${rootDir}/distrib",
    ciDir:      "${rootDir}/ci-scripts",

    buildDir:     "${rootDir}/build",
    buildHomeDir: "${buildDir}/home",
    buildShDir:   "${buildDir}/scripts",
    buildSh:      "${buildShDir}/build.sh",

    smokeDir:     "${rootDir}/smoke",
    smokeHomeDir: "${smokeDir}/home",
    smokeShDir:   "${smokeDir}/scripts",
    smokeSh:      "${smokeShDir}/smoke.sh",
  ]

  // create list of all dir names that can be pre-created -- excludes
  // eupsBuildDir
  l.'Dirs' = l.findAll { x -> x.key =~ /Dir$/ }.keySet().toList()

  l.'eupsBuildDir' = eupsBuildDir(l.buildDir, bc.menv)

  return layout
}
