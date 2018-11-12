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
  def matrix = [:]
  ap.ap_verify.datasets.each { ds ->
    def runSlug = datasetSlug(ds)

    matrix[runSlug] = {
      verifyDataset(
        dataset: ds,
        dockerImage: dockerImage,
        slug: runSlug,
        wipeout: wipeout,
      )
    }
  }

  stage('ap_verify matrix') {
    parallel(matrix)
  }
} // notify.wrap

/**
 * Prepare, execute, and record results of a validation_drp run.
 *
 * @param p Map
 * @param p.dataset Map Dataset configuration.
 * @param p.dockerImage String
 * @param p.slug String Name of dataset.
 * @param p.wipeout Boolean
 */
def void verifyDataset(Map p) {
  util.requireMapKeys(p, [
    'dataset',
    'dockerImage',
    'slug',
    'wipeout',
  ])
  def ds = p.dataset

  // Eg.: lsst/ap_verify_ci_hits2015 -> ap_verify_ci_hits2015
  def gitRepoName = ds.github_repo.split('/')[1]

  def run = {
    // note that pwd() must be run inside of a node {} block
    def baseDir    = "${pwd()}/${p.slug}"
    def runDir     = "${baseDir}/ap_verify"
    def datasetDir = "${baseDir}/${gitRepoName}"
    def homeDir    = "${baseDir}/home"

    try {
      dir(baseDir) {
        // empty ephemeral dirs at start of build
        util.emptyDirs([
          homeDir,
          runDir,
        ])

        // clone dataset
        dir(datasetDir) {
          timeout(time: ds.clone_timelimit, unit: 'MINUTES') {
            util.checkoutLFS(
              githubSlug: ds.github_repo,
              gitRef: ds.git_ref,
            )
          }
        }

        // process dataset
        util.insideDockerWrap(
          image: p.dockerImage,
          pull: true,
        ) {
          runApVerify(
            runDir: runDir,
            dataset: ds,
            datasetDir: datasetDir,
            homeDir: homeDir,
          )
        } // inside
      } // dir
    } finally {
      // collect artifacts
      util.record([
        "${runDir}/**/*.log",
        "${runDir}/**/*.json",
      ])
    }
  } // run

  retry(ds.retries) {
    node('docker') {
      // total allowed runtime for this "try" including cloning a test data /
      // git-lfs repo
      timeout(time: ds.run_timelimit, unit: 'MINUTES') {
        if (p.wipeout) {
          deleteDir()
        }

        run()
      } // timeout
    } // node
  } // retry
} // verifyDataset

/**
 * Generate a "slug" to describe this dataset including branch name.
 *
 * @param p Map
 */
def String datasetSlug(Map ds) {
  def slug = ds.name
  if (ds.git_ref != 'master') {
    slug += "-" + ds.git_ref.tr('/', '_')
  }

  return slug.toLowerCase()
}

/**
 * Run ap_verify driver script.
 *
 * @param p Map
 * @param p.runDir String runtime cwd
 * @param p.dataset String full name of the validation dataset
 * @param p.datasetDir String path to validation dataset
 */
def void runApVerify(Map p) {
  util.requireMapKeys(p, [
    'runDir',
    'dataset',
    'datasetDir',
    'homeDir',
  ])

  // run drp driver script
  withEnv([
    "RUN_DIR=${p.runDir}",
    "DATASET_NAME=${p.dataset.name}",
    "DATASET_DIR=${p.datasetDir}",
    "HOME=${p.homeDir}",
  ]) {
    util.bash '''
      source /opt/lsst/software/stack/loadLSST.bash
      setup ap_verify

      cd ${DATASET_DIR}
      setup -k -r .

      cd ${RUN_DIR}
      run_ci_dataset.sh -d ${DATASET_NAME}
    '''
  } // withEnv
}
