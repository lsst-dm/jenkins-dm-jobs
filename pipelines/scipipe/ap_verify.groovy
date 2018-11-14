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
    sqre = util.sqreConfig()
    ap = util.apVerifyConfig()
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
  def jobConf     = ap
  def jobConfName = 'ap_verify'
  def defaults    = jobConf."$jobConfName".defaults
  def matrix      = [:]
  jobConf."$jobConfName".configs.each { conf ->
    // apply defaults
    conf = defaults + conf
    conf.dataset.display_name = displayName(conf.dataset)

    def runSlug = datasetSlug(conf)

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
 * `name` to use to refer to dataset/code
 *
 * @param m Map
 */
def String displayName(Map m) {
  m.display_name ?: m.name
}

/**
 * Prepare, execute, and record results of a validation_drp run.
 *
 * @param p Map
 * @param p.config Map Dataset configuration.
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

  // Eg.: lsst/ap_verify_ci_hits2015 -> ap_verify_ci_hits2015
  def gitRepoName = ds.github_repo.split('/')[1]

  def run = {
    // note that pwd() must be run inside of a node {} block
    def jobDir           = pwd()
    def datasetDir       = "${jobDir}/datasets/${ds.name}"
    def baseDir          = "${jobDir}/${p.slug}"
    def homeDir          = "${baseDir}/home"
    def runDir           = "${baseDir}/run"
    def fakeLsstswDir    = "${baseDir}/lsstsw-fake"
    def fakeManifestFile = "${fakeLsstswDir}/build/manifest.txt"
    def fakeReposFile    = "${fakeLsstswDir}/etc/repos.yaml"

    docker.image(p.dockerImage).pull()
    def labels = util.shJson """
      docker inspect --format '{{json .Config.Labels }}' ${p.dockerImage}
    """

    if (!labels.VERSIONDB_MANIFEST_ID) {
      missingDockerLabel 'VERSIONDB_MANIFEST_ID'
    }

    String manifestId = labels.VERSIONDB_MANIFEST_ID

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

    // clone dataset
    dir(datasetDir) {
      timeout(time: ds.clone_timelimit, unit: 'MINUTES') {
        util.checkoutLFS(
          githubSlug: ds.github_repo,
          gitRef: ds.git_ref,
        )
      } // timeout
    } // dir

    // process dataset
    util.insideDockerWrap(
      image: p.dockerImage,
      pull: true,
      args: "-v ${datasetDir}:${datasetDir}",
    ) {
      runApVerify(
        runDir: runDir,
        dataset: ds,
        datasetDir: datasetDir,
        homeDir: homeDir,
        archiveDir: jobDir,
      )

      // push results to squash
      if (p.squashPush) {
        def files = []
        dir(runDir) {
          files = findFiles(glob: '**/ap_verify.*.verify.json')
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
    } // insideDockerWrap
  } // run

  retry(conf.retries) {
    node('docker') {
      // total allowed runtime for this "try" including cloning a test data /
      // git-lfs repo
      timeout(time: conf.run_timelimit, unit: 'MINUTES') {
        if (p.wipeout) {
          deleteDir()
        }

        run()
      } // timeout
    } // node
  } // retry
} // verifyDataset

/**
 * Run ap_verify driver script.
 *
 * @param p Map
 * @param p.runDir String runtime cwd
 * @param p.dataset String full name of the validation dataset
 * @param p.datasetDir String path to validation dataset
 * @param p.homemDir String path to $HOME -- where to put dotfiles
 * @param p.archiveDir String path from which to archive artifacts
 */
def void runApVerify(Map p) {
  util.requireMapKeys(p, [
    'runDir',
    'dataset',
    'datasetDir',
    'homeDir',
    'archiveDir',
  ])

  def run = {
    util.bash '''
      source /opt/lsst/software/stack/loadLSST.bash
      setup ap_verify

      cd ${DATASET_DIR}
      setup -k -r .

      cd ${RUN_DIR}
      run_ci_dataset.sh -d ${DATASET_NAME}
    '''
  }

  // run drp driver script
  withEnv([
    "RUN_DIR=${p.runDir}",
    "DATASET_NAME=${p.dataset.name}",
    "DATASET_DIR=${p.datasetDir}",
    "HOME=${p.homeDir}",
  ]) {
    try {
      dir(p.runDir) {
        run()
      }
    } finally {
      util.record(util.xz([
        "${p.runDir}/**/*.log",
        "${p.runDir}/**/*.json",
      ]))
    } // try
  } // withEnv
} // runApVerify

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
