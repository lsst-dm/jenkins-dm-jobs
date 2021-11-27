node('jenkins-manager') {
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
    'REF',
    'DOCKER_IMAGE',
    'NO_PUSH',
    'WIPEOUT',
  ])

  String ref         = params.REF
  String dockerImage = params.DOCKER_IMAGE
  Boolean noPush     = params.NO_PUSH
  Boolean wipeout    = params.WIPEOUT

  // run multiple datasets, if defined, in parallel
  def jobConf     = ap
  def jobConfName = 'ap_verify'
  def matrix      = [:]
  def defaults    = jobConf."$jobConfName".defaults
  jobConf."$jobConfName".configs.each { conf ->
    // apply defaults
    conf = defaults + conf
    if (ref) {
      conf.code.git_ref = ref
    } else {
      if (conf.code) {
         conf.code = null
      }
    }
    if (conf.code) {
      conf.code.display_name = displayName(conf.code)
    }
    conf.dataset.display_name = displayName(conf.dataset)

    // note that `:` seems to break python imports and `*` seems to break the
    // butler
    def runSlug = "${datasetSlug(conf)}^${genSlug(conf)}^${codeSlug(conf)}"

    // these configs are used to construct a shell command line; guard against code injection
    if (!(conf.dataset.name ==~ /[\w-.:]+/)) {
      error "Ill-formed dataset name ${conf.dataset.name}"
    }
    if (conf.gen) {
      conf.gen = conf.gen as int
    } else {
      error "Missing gen keyword in ${runSlug}"
    }

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
 * Generate a "slug" to describe the Middleware generation.
 *
 * @param c Map
 */
def String genSlug(Map conf) {
  def slug = "gen${conf.gen}"
  return slug.toLowerCase()
}

/**
 * Generate a "slug" to describe the verification code including branch name.
 *
 * @param c Map
 */
def String codeSlug(Map conf) {
  def code = conf.code

  def name = 'ap_verify'
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
 * Prepare, execute, and record results of an ap_verify run.
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
  def code = conf.code

  // code.name is required in order to build code
  Boolean buildCode = code?.name

  def run = {
    // note that pwd() must be run inside of a node {} block
    def jobDir           = pwd()
    def datasetDir       = "${jobDir}/datasets/${ds.name}"
    def ciDir            = "${jobDir}/ci-scripts"
    def baseDir          = "${jobDir}/${p.slug}"
    // the code clone needs to be under the long winded path for archiving
    def codeDir          = buildCode ? "${baseDir}/${code.name}" : ''
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

    String manifestId = labels.VERSIONDB_MANIFEST_ID
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
      } // timeout
    } // dir

    // clone code
    if (buildCode) {
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
    }

    // process dataset
    util.insideDockerWrap(
      image: p.dockerImage,
      pull: true,
      args: "-v ${datasetDir}:${datasetDir}",
    ) {
      if (buildCode) {
        buildAp(
          codeDir: codeDir,
          ciDir: ciDir,
          homeDir: homeDir,
          runSlug: p.slug,
          lsstCompiler: lsstCompiler,
          archiveDir: jobDir,
        )
      }

      runApVerify(
        runDir: runDir,
        dataset: ds,
        gen: conf.gen,
        datasetDir: datasetDir,
        homeDir: homeDir,
        archiveDir: jobDir,
        codeDir: codeDir,
      )

      // push results to squash
      if (p.squashPush) {
        switch (conf.gen) {
          case 3:
            // Path is partially hard-coded in ap_verify.
            def gen3Dir = util.joinPath(ds.name, 'repo')
            def collection = 'ap_verify-output'
            util.runGen3ToJob(
              runDir: runDir,
              gen3Dir: gen3Dir,
              collectionName: collection,
              namespace: '',
              datasetName: ds.name,
            )
            // Delegate upload to Gen 2 code
          case 2:
            def files = []
            dir(runDir) {
              files = findFiles(glob: '**/*.verify.json')
            }

            def codeRef = buildCode ? code.git_ref : "master"
            withEnv([
              "refs=${codeRef}",
            ]) {
              files.each { f ->
                util.runDispatchVerify(
                  runDir: runDir,
                  lsstswDir: fakeLsstswDir,
                  datasetName: ds.name,
                  resultFile: f,
                  squashUrl: sqre.squash.url,
                )
              }
            }
            break
          default:
            currentBuild.result = 'UNSTABLE'
            echo "SQuaSH upload not supported for Gen ${conf.gen} pipeline framework; skipping"
        }
      }
    } // insideDockerWrap
  } // run

  retry(conf.retries) {
    util.nodeWrap('docker') {
      // total allowed runtime for this "try" including cloning a test data /
      // git-lfs repo
      timeout(time: conf.run_timelimit, unit: 'MINUTES') {
        if (p.wipeout) {
          deleteDir()
        }

        run()
      } // timeout
    } // util.nodeWrap
  } // retry
} // verifyDataset

/**
 * Build ap_verify
 *
 * @param p Map
 * @param p.homemDir String path to $HOME -- where to put dotfiles
 * @param p.codeDir String path to ap_verify (code)
 * @param p,runSlug String short name to describe this ap_verify run
 * @param p.ciDir String
 * @param p.lsstCompiler String
 * @param p.archiveDir String path from which to archive artifacts
 */
def void buildAp(Map p) {
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
 * Run ap_verify driver script.
 *
 * @param p Map
 * @param p.runDir String runtime cwd
 * @param p.dataset Map Dataset block in the format of ap_verify.yaml.
 * @param p.gen Integer pipeline generation to run
 * @param p.datasetDir String path to validation dataset
 * @param p.homemDir String path to $HOME -- where to put dotfiles
 * @param p.archiveDir String path from which to archive artifacts
 */
def void runApVerify(Map p) {
  util.requireMapKeys(p, [
    'runDir',
    'dataset',
    'gen',
    'datasetDir',
    'homeDir',
    'archiveDir',
    'codeDir',
  ])

  def run = {
    util.bash '''
      source /opt/lsst/software/stack/loadLSST.bash
      # if CODE_DIR is defined, set that up instead of the default ap_verify
      # product
      echo "This is CODE_DIR ${CODE_DIR}"
      if [[ -n $CODE_DIR ]]; then
        setup -k -r "$CODE_DIR"
      else
        setup ap_verify
      fi

      cd ${DATASET_DIR}
      setup -k -r .

      cd ${RUN_DIR}
      # -p ignored if not -g 3
      run_ci_dataset.sh -d ${DATASET_NAME} -g ${DATASET_GEN} -p ${DATASET_PIPE}
    '''
  }

  // run ap driver script
  withEnv([
    "RUN_DIR=${p.runDir}",
    "DATASET_NAME=${p.dataset.name}",
    "DATASET_GEN=${p.gen}",
    "DATASET_PIPE=${p.dataset.gen3_pipeline}",
    "DATASET_DIR=${p.datasetDir}",
    "HOME=${p.homeDir}",
    "CODE_DIR=${p.codeDir}",
  ]) {
    try {
      dir(p.runDir) {
        run()
      }
    } finally {
      dir(p.archiveDir) {
        util.record(util.xz([
          "${p.runDir}/**/*.log",
          "${p.runDir}/**/*.json",  // Gen 2
          "${p.runDir}/**/metricvalue_*/**/*.yaml",  // Gen 3
          "${p.runDir}/**/*.db",
        ]))
      }
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
