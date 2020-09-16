// Consideration has been given to renaming this job to scipipe/validate_drp
// but there is concern about breakage of links back to previous biulds from
// squash.  A rename needs to be coordinated with a simultaneous fixing up of
// the squash database.

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
    scipipe = util.scipipeConfig()
    sqre = util.sqreConfig() // for squash config
    drp = util.validateDrpGen3Config()
  }
}

notify.wrap {
  util.requireParams([
    'DOCKER_IMAGE',
    'NO_PUSH',
    'WIPEOUT',
  ])

  String dockerImage = params.DOCKER_IMAGE
  Boolean noPush     = params.NO_PUSH
  Boolean wipeout    = params.WIPEOUT

  // run multiple datasets, if defined, in parallel
  def jobConf     = drp
  def jobConfName = 'validate_drp_gen3'
  def matrix      = [:]
  def defaults    = jobConf."$jobConfName".defaults
  jobConf."$jobConfName".configs.each { conf ->
    // apply defaults
    conf = defaults + conf
    if (conf.code) {
      conf.code.display_name = displayName(conf.code)
    }
    conf.dataset.display_name = displayName(conf.dataset)

    // note that `:` seems to break python imports and `*` seems to break the
    // butler
    def runSlug = "${datasetSlug(conf)}^${codeSlug(conf)}"

    matrix[runSlug] = {
      verifyDataset(
        config: conf,
        dockerImage: dockerImage,
        // only push if build param & yaml config == true
        squashPush: (!noPush) && conf.squash_push,
        slug: runSlug,
        wipeout: wipeout,
      )
    }
  }

  stage("${jobConfName} matrix") {
    parallel(matrix)
  }
} // notify.wrap

/**
 * Generate a "slug" to describe this dataset including branch name.
 *
 * @param conf Map
 */
def String datasetSlug(Map conf) {
  def ds = conf.dataset
  def slug = "${ds.display_name}-${ds.git_ref.tr('/', '_')}"
  return slug.toLowerCase()
}

/**
 * Generate a "slug" to describe the verification code including branch name.
 *
 * @param c Map
 */
def String codeSlug(Map conf) {
  def code = conf.code

  def name = 'validate_drp_gen3'
  def ref = 'installed'

  if (code) {
    name = code.display_name
  }
  if (code?.github_repo) {
    name = code.name
    ref  = code.git_ref.tr('/', '_')
  }
  def slug = "${name}-${ref}".toLowerCase()
  return slug.toLowerCase()
}

/**
 * `name` to use to refer to dataset/code
 *
 * @param m Map
 */
def String displayName(Map m) {
  m.display_name ?: m.name
}

/**
 * Prepare, execute, and record results of a validation_drp_gen3 run.
 *
 * @param p Map
 * @param p.config Map
 * @param p.dockerImage String
 * @param p.squashPush Boolean
 * @param p.slug String Name of dataset.
 * @param p.wipeout Boolean
 */
def void verifyDataset(Map p) {
  util.requireMapKeys(p, [
    'config',
    'dockerImage',
    'slug',
    'squashPush',
    'wipeout',
  ])

  def conf = p.config
  def ds   = conf.dataset
  def code = conf.code

  // code.name is required in order to build code

  def run = {
    // note that pwd() must be run inside of a node {} block
    def jobDir           = pwd()
    def datasetDir       = "${jobDir}/datasets/${ds.name}"
    def ciDir            = "${jobDir}/ci-scripts"
    def baseDir          = "${jobDir}/${p.slug}"
    // the code clone needs to be under the long winded path for archiving
    def codeDir          = "${baseDir}/${code.name}"
    def homeDir          = "${baseDir}/home"
    def runDir           = "${baseDir}/run"
    def fakeLsstswDir    = "${baseDir}/lsstsw-fake"

    docker.image(p.dockerImage).pull()
    def labels = util.shJson """
      docker inspect --format '{{json .Config.Labels }}' ${p.dockerImage}
    """

    if (!labels.VERSIONDB_MANIFEST_ID) {
      missingDockerLabel 'VERSIONDB_MANIFEST_ID'
    }
    if (!labels.LSST_COMPILER) {
      missingDockerLabel 'LSST_COMPILER'
    }

    String manifestId   = labels.VERSIONDB_MANIFEST_ID
    String lsstCompiler = labels.LSST_COMPILER

    // empty ephemeral dirs at start of build
    util.emptyDirs([
      homeDir,
      runDir,
    ])

    // stage manifest.txt early so we don't risk a long processing run and
    // then fail setting up to run dispatch_verify.py
    stageFakeLsstsw(
      fakeLsstswDir: fakeLsstswDir,
      manifestId: manifestId,
      archiveDir: jobDir,
    )

    dir(ciDir) {
      util.cloneCiScripts()
    }

    // clone dataset
    dir(datasetDir) {
      timeout(time: ds.clone_timelimit, unit: 'MINUTES') {
        util.checkoutLFS(
          githubSlug: ds.github_repo,
          gitRef: ds.git_ref,
        )
      }
    } // dir

    // clone code
    dir(codeDir) {
      timeout(time: code.clone_timelimit, unit: 'MINUTES') {
        // the simplier git step doesn't support 'CleanBeforeCheckout'
        def codeRepoUrl = util.githubSlugToUrl(code.github_repo)
        def codeRef     = code.git_ref

        checkout(
          scm: [
            $class: 'GitSCM',
            branches: [[name: "*/${codeRef}"]],
            doGenerateSubmoduleConfigurations: false,
            extensions: [[$class: 'CleanBeforeCheckout']],
            submoduleCfg: [],
            userRemoteConfigs: [[url: codeRepoUrl]]
          ],
          changelog: false,
          poll: false,
        )
      } // timeout
    } // dir

    util.insideDockerWrap(
      image: p.dockerImage,
      pull: true,
      args: "-v ${datasetDir}:${datasetDir} -v ${ciDir}:${ciDir}",
    ) {
      buildDrpGen3(
        codeDir: codeDir,
        ciDir: ciDir,
        homeDir: homeDir,
        runSlug: p.slug,
        lsstCompiler: lsstCompiler,
        archiveDir: jobDir,
      )

      runDrpGen3(
        runDir: runDir,
        codeDir: codeDir,
        ciDir: ciDir,
        datasetName: ds.name,
        datasetDir: datasetDir,
        archiveDir: jobDir,
      )

      // push results to squash
      if (p.squashPush) {
        def files = []
        dir(runDir) {
          files = findFiles(glob: 'validate_drp_*.json')
        }

        files.each { f ->
          util.runDispatchVerify(
            runDir: runDir,
            lsstswDir: fakeLsstswDir,
            datasetName: ds.name,
            resultFile: f,
          )
        }
      }
    } // inside
  } // run

  // retrying is important as there is a good chance that the dataset will
  // fill the disk up
  retry(conf.retries) {
    try {
      util.nodeWrap('docker') {
        timeout(time: conf.run_timelimit, unit: 'MINUTES') {
          if (p.wipeout) {
            deleteDir()
          }

          run()
        } // timeout
      } // util.nodeWrap
    } catch(e) {
      runNodeCleanup()
      throw e
    } // try
  } // retry
} // verifyDataset

/**
 * Trigger jenkins-node-cleanup (disk space) and wait for it to complete.
 */
def void runNodeCleanup() {
  build job: 'sqre/infra/jenkins-node-cleanup',
    wait: true
}

/**
 * Build validate_drp
 *
 * @param p Map
 * @param p.homemDir String path to $HOME -- where to put dotfiles
 * @param p.codeDir String path to validate_drp (code)
 * @param p,runSlug String short name to describe this drp run
 * @param p.ciDir String
 * @param p.lsstCompiler String
 * @param p.archiveDir String path from which to archive artifacts
 */
def void buildDrpGen3(Map p) {
  util.requireMapKeys(p, [
    'homeDir',
    'codeDir',
    'runSlug',
    'ciDir',
    'lsstCompiler',
    'archiveDir',
  ])

  def run = {
    util.bash '''
      set +o xtrace

      source "${CI_DIR}/ccutils.sh"
      cc::setup_first "$LSST_COMPILER"

      source /opt/lsst/software/stack/loadLSST.bash
      setup -k -r .

      set -o xtrace

      scons
    '''
  }

  withEnv([
    "HOME=${p.homeDir}",
    // keep eups from polluting the jenkins role user dotfiles
    "EUPS_USERDATA=${p.homeDir}/.eups_userdata",
    "CODE_DIR=${p.codeDir}",
    "CI_DIR=${p.ciDir}",
    "LSST_JUNIT_PREFIX=${p.runSlug}",
    "LSST_COMPILER=${p.lsstCompiler}",
  ]) {
    try {
      dir(p.codeDir) {
        run()
      }
    } finally {
      dir(p.archiveDir) {
        util.record([
          "${p.codeDir}/**/*.log",
          "${p.codeDir}/**/*.failed",
          "${p.codeDir}/**/pytest-*.xml",
        ])
        util.junit(["${p.codeDir}/**/pytest-*.xml"])
      }
    } // try
  } // withEnv
}

/**
 * Run validate_drp_gen3 driver script.
 *
 * @param p Map
 * @param p.runDir String runtime cwd for validate_drp
 * @param p.datasetName String full name of the validation dataset
 * @param p.datasetDir String path to validation dataset
 * @param p.ciDir String
 * @param p.codeDir (Optional) String path to validate_drp (code)
 * @param p.archiveDir String path from which to archive artifacts
 */
def void runDrpGen3(Map p) {
  util.requireMapKeys(p, [
    'runDir',
    'codeDir',
    'datasetName',
    'datasetDir',
    'ciDir',
    'archiveDir',
  ])


  withEnv([
    "LSST_VALIDATE_DRP_GEN3_CODE_DIR=${p.codeDir}",
    "LSST_VALIDATE_DRP_GEN3_DATASET=${p.datasetName}",
    "LSST_VALIDATE_DRP_GEN3_DATASET_DIR=${p.datasetDir}",
    "LSST_CI_SCRIPTS_DIR=${p.ciDir}",
  ]) {
    try {
      dir(p.runDir) {
        // run drp driver script
        util.bash '''
          set +o xtrace
          source /opt/lsst/software/stack/loadLSST.bash
          set -o xtrace

          "${LSST_CI_SCRIPTS_DIR}/run_validate_drp_gen3.sh"
        '''
      }
    } finally {
      dir(p.archiveDir) {
        util.record(util.xz([
          "${p.runDir}/**/*.log",
          "${p.runDir}/**/validate_drp_*.json",
        ]))
      }
    } // try
  } // withEnv
} // runDrpGen3

def void missingDockerLabel(String label) {
  error "docker ${label} label is missing"
}

/**
 * bootstrap a fake lsstsw clone and archive it
 *
 * @param p Map
 * @param p.fakeLsstswDir String path to the "clone"
 * @param p.manifestId String versiondb manifest id
 * @param p.archiveDir String path from which to archive artifacts
 */
def void stageFakeLsstsw(Map p) {
  util.requireMapKeys(p, [
    'fakeLsstswDir',
    'manifestId',
    'archiveDir',
  ])

  try {
    util.createFakeLsstswClone(
      fakeLsstswDir: p.fakeLsstswDir,
      manifestId: p.manifestId,
    )
  } finally {
    dir(p.archiveDir) {
      util.record(["${p.fakeLsstswDir}/**"])
    }
  } // try
} // stageFakeLsstsw
