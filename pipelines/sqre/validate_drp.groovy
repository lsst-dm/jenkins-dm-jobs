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
    drp = util.validateDrpConfig()
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
  def defaults = drp.validate_drp.defaults
  drp.validate_drp.configs.each { conf ->
    // apply defaults
    conf = defaults + conf
    if (conf.code) {
      conf.code.display_name = displayName(conf.code)
    }
    conf.dataset.display_name = displayName(conf.dataset)

    // note that `:` seems to break python imports and `*` seems to break the
    // buttler
    def runSlug = "${datasetSlug(conf)}^${codeSlug(conf)}"

    matrix[runSlug] = {
      verifyDataset(
        config: conf,
        dockerImage: dockerImage,
        // only push if build param & yaml config == true
        noPush: noPush && conf.squash_push,
        slug: runSlug,
        wipeout: wipeout,
      )
    }
  }

  stage('validate_drp matrix') {
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

  def name = 'validate_drp'
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
 * Prepare, execute, and record results of a validation_drp run.
 *
 * @param p Map
 * @param p.config String
 * @param p.dockerImage String
 * @param p.noPush Boolean
 * @param p.slug String Name of dataset.
 * @param p.wipeout Boolean
 */
def void verifyDataset(Map p) {
  util.requireMapKeys(p, [
    'config',
    'dockerImage',
    'slug',
    'noPush',
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
    def fakeManifestFile = "${fakeLsstswDir}/build/manifest.txt"
    def fakeReposFile    = "${fakeLsstswDir}/etc/repos.yaml"

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
      "${fakeLsstswDir}/build",
      "${fakeLsstswDir}/etc",
      homeDir,
      runDir,
    ])

    // stage manifest.txt early so we don't risk a long processing run and
    // then fail setting up to run dispatch_verify.py
    util.downloadManifest(
      destFile: fakeManifestFile,
      manifestId: manifestId,
    )
    util.downloadRepos(destFile: fakeReposFile)
    util.record([fakeManifestFile, fakeReposFile])

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

    util.insideDockerWrap(
      image: p.dockerImage,
      pull: true,
      args: "-v ${datasetDir}:${datasetDir} -v ${ciDir}:${ciDir}",
    ) {
      if (buildCode) {
        buildDrp(
          homeDir: homeDir,
          codeDir: codeDir,
          runSlug: p.slug,
          ciDir: ciDir,
          lsstCompiler: lsstCompiler,
        )
      }

      runDrp(
        runDir: runDir,
        codeDir: codeDir,
        datasetName: ds.name,
        datasetDir: datasetDir,
      )

      // push results to squash
      runDispatchqa(
        runDir: runDir,
        codeDir: codeDir,
        lsstswDir: fakeLsstswDir,
        squashDatasetSlug: displayName(ds),
        noPush: p.noPush
      )
    } // inside
  } // run

  // retrying is important as there is a good chance that the dataset will
  // fill the disk up
  retry(ds.retries) {
    try {
      node('docker') {
        timeout(time: conf.run_timelimit, unit: 'MINUTES') {
          if (p.wipeout) {
            deleteDir()
          }

          run()
        } // timeout
      } // node
    } catch(e) {
      runNodeCleanup()
      throw e
    }
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
 * @param homemDir String path to $HOME -- where to put dotfiles
 * @param codeDir String path to validate_drp (code)
 * @param runSlug String short name to describe this drp run
 * @param ciDir String
 * @param lsstCompiler String
 */
def void buildDrp(Map p) {
  util.requireMapKeys(p, [
    'homeDir',
    'codeDir',
    'runSlug',
    'ciDir',
    'lsstCompiler',
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
      util.record([
        "${p.codeDir}/**/*.log",
        "${p.codeDir}/**/*.failed",
        "${p.codeDir}/**/pytest-*.xml",
      ])
      util.junit(["${p.codeDir}/**/pytest-*.xml"])
    }
  } // withEnv
}

/**
 * XXX this monster should be moved into an external shell script.
 *
 * Run validate_drp driver script.
 *
 * @param p Map
 * @param p.runDir String runtime cwd for validate_drp
 * @param p.datasetName String full name of the validation dataset
 * @param p.datasetDir String path to validation dataset
 * @param p.codeDir (Optional) String path to validate_drp (code)
 */
def void runDrp(Map p) {
  util.requireMapKeys(p, [
    'runDir',
    'datasetName',
    'datasetDir',
  ])

  p = [codeDir: ''] + p

  // run drp driver script
  def run = {
    util.bash '''
      find_mem() {
        # Find system available memory in GiB
        local os
        os=$(uname)

        local sys_mem=""
        case $os in
          Linux)
            [[ $(grep MemAvailable /proc/meminfo) =~ \
               MemAvailable:[[:space:]]*([[:digit:]]+)[[:space:]]*kB ]]
            sys_mem=$((BASH_REMATCH[1] / 1024**2))
            ;;
          Darwin)
            # I don't trust this fancy greppin' an' matchin' in the shell.
            local free=$(vm_stat | grep 'Pages free:'     | \
              tr -c -d [[:digit:]])
            local inac=$(vm_stat | grep 'Pages inactive:' | \
              tr -c -d [[:digit:]])
            sys_mem=$(( (free + inac) / ( 1024 * 256 ) ))
            ;;
          *)
            >&2 echo "Unknown uname: $os"
            exit 1
            ;;
        esac

        echo "$sys_mem"
      }

      # find the maximum number of processes that may be run on the system
      # given the the memory per core ratio in GiB -- may be expressed in
      # floating point.
      target_cores() {
        local mem_per_core=${1:-1}

        local sys_mem=$(find_mem)
        local sys_cores
        sys_cores=$(getconf _NPROCESSORS_ONLN)

        # bash doesn't support floating point arithmetic
        local target_cores
        #target_cores=$(echo "$sys_mem / $mem_per_core" | bc)
        target_cores=$(awk "BEGIN{ print int($sys_mem / $mem_per_core) }")
        [[ $target_cores > $sys_cores ]] && target_cores=$sys_cores

        echo "$target_cores"
      }

      set +o xtrace
      source /opt/lsst/software/stack/loadLSST.bash

      # if CODE_DIR is defined, set that up instead of the default validate_drp
      # product
      if [[ -n $CODE_DIR ]]; then
        setup -k -r "$CODE_DIR"
      else
        setup validate_drp
      fi

      setup -k -r "$DATASET_DIR"
      set -o xtrace

      case "$DATASET" in
        validation_data_cfht)
          RUN="$VALIDATE_DRP_DIR/examples/runCfhtTest.sh"
          RESULTS=(
            Cfht_output_r.json
          )
          LOGS=(
            'Cfht/singleFrame.log'
            'job_validate_drp.log'
          )
          ;;
        validation_data_decam)
          RUN="$VALIDATE_DRP_DIR/examples/runDecamTest.sh"
          RESULTS=(
            Decam_output_z.json
          )
          LOGS=(
            'Decam/singleFrame.log'
            'job_validate_drp.log'
          )
          ;;
        validation_data_hsc)
          RUN="$VALIDATE_DRP_DIR/examples/runHscTest.sh"
          RESULTS=(
            Hsc_output_HSC-I.json
            Hsc_output_HSC-R.json
            Hsc_output_HSC-Y.json
          )
          LOGS=(
            'Hsc/singleFrame.log'
            'job_validate_drp.log'
          )
          ;;
        *)
          >&2 echo "Unknown DATASET: ${DATASET}"
          exit 1
          ;;
      esac

      # pipe_drivers mpi implementation uses one core for orchestration, so we
      # need to set NUMPROC to the number of cores to utilize + 1
      MEM_PER_CORE=2.0
      export NUMPROC=$(($(target_cores $MEM_PER_CORE) + 1))

      set +e
      "$RUN" -- --noplot
      run_status=$?
      set -e

      echo "${RUN##*/} - exit status: ${run_status}"

      # bail out if the drp output file is missing
      if [[ ! -e  ${RESULTS[0]} ]]; then
        echo "drp result file does not exist: ${RESULTS[0]}"
        exit 1
      fi

      exit $run_status
    '''
  } // run

  withEnv([
    "CODE_DIR=${p.codeDir}",
    "DATASET=${p.datasetName}",
    "DATASET_DIR=${p.datasetDir}",
  ]) {
    try {
      dir(p.runDir) {
        run()
      }
    } finally {
      // archive from root of ws
      util.record(util.xz([
        "${p.runDir}/**/*.log",
        "${p.runDir}/**/*_output_*.json",
      ]))
    } // dir
  } // withEnv
} // runDrp

/**
 * push DRP results to squash using dispatch-verify.
 *
 * @param p Map
 * @param p.resultPath
 * @param p.lsstswDir String Path to (the fake) lsstsw dir
 * @param p.squashDatasetSlug String The dataset "short" name.  Eg., cfht
 * instead of validation_data_cfht.
 * @param p.noPush Boolean if true, do not attempt to push data to squash.
 * Reguardless of that value, the output of the characterization report is recorded
 */
def void runDispatchqa(Map p) {
  util.requireMapKeys(p, [
    'runDir',
    'lsstswDir',
    'squashDatasetSlug',
    'noPush',
  ])

  p = [codeDir: ''] + p

  def run = {
    util.bash '''
      set +o xtrace
      source /opt/lsst/software/stack/loadLSST.bash
      # if CODE_DIR is defined, set that up instead of the default validate_drp
      # product
      if [[ -n $CODE_DIR ]]; then
        setup -k -r "$CODE_DIR"
      else
        setup validate_drp
      fi
      set -o xtrace

      # compute characterization report
      reportPerformance.py \
        --output_file="$dataset"_char_report.rst \
        *_output_*.json
    '''

    if (!p.noPush) {
      util.bash '''
        set +o xtrace
        source /opt/lsst/software/stack/loadLSST.bash
        setup verify
        set -o xtrace

        # submit via dispatch_verify
        for file in $( ls *_output_*.json ); do
          dispatch_verify.py \
            --env jenkins \
            --lsstsw "$LSSTSW_DIR" \
            --url "$SQUASH_URL" \
            --user "$SQUASH_USER" \
            --password "$SQUASH_PASS" \
            $file
        done
      '''
    }
  } // run

  /*
  These are already present under pipeline:
  - BUILD_ID
  - BUILD_URL

  This var was defined automagically by matrixJob and now must be manually
  set:
  - dataset
  */
  withEnv([
    "LSSTSW_DIR=${p.lsstswDir}",
    "CODE_DIR=${p.codeDir}",
    "NO_PUSH=${p.noPush}",
    "dataset=${p.squashDatasetSlug}",
    "SQUASH_URL=${sqre.squash.url}",
  ]) {
    withCredentials([[
      $class: 'UsernamePasswordMultiBinding',
      credentialsId: 'squash-api-user',
      usernameVariable: 'SQUASH_USER',
      passwordVariable: 'SQUASH_PASS',
    ]]) {
      try {
        dir(p.runDir) {
          run()
        }
      } finally {
        // archive from root of ws
        util.record(util.xz(["${p.runDir}/**/*_char_report.rst"]))
      }
    } // withCredentials
  } // withEnv
} // runDispatchqa

def void missingDockerLabel(String label) {
  error "docker ${label} label is missing"
}
