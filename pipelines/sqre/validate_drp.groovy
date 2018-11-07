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
  drp.validate_drp.datasets.each { ds ->
    def runSlug = datasetSlug(ds)

    // apply defaults
    ds = defaults + ds

    matrix[runSlug] = {
      verifyDataset(
        dataset: ds,
        dockerImage: dockerImage,
        // only push if build param & yaml config == true
        noPush: noPush && ds.squash_push,
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
 * @param p Map
 */
def String datasetSlug(Map ds) {
  def slug = ds.display_name
  if (ds.data.git_ref != 'master') {
    slug += "-" + ds.data.git_ref.tr('/', '_')
  }

  return slug.toLowerCase()
}

/**
 * Prepare, execute, and record results of a validation_drp run.
 *
 * @param p Map
 * @param p.dataset String
 * @param p.dockerImage String
 * @param p.noPush Boolean
 * @param p.slug String Name of dataset.
 * @param p.wipeout Boolean
 */
def void verifyDataset(Map p) {
  util.requireMapKeys(p, [
    'dataset',
    'dockerImage',
    'slug',
    'noPush',
    'wipeout',
  ])

  def ds         = p.dataset
  def dsRepoName = ds.name

  def run = {
    // note that pwd() must be run inside of a node {} block
    def baseDir           = "${pwd()}/${p.slug}"
    def codeDir           = "${baseDir}/validate_drp"
    def datasetDir        = "${baseDir}/${ds.name}"
    def homeDir           = "${baseDir}/home"
    def runDir            = "${baseDir}/run"
    def archiveDir        = "${baseDir}/archive"
    def datasetArchiveDir = "${archiveDir}/${ds.name}"
    def fakeLsstswDir     = "${baseDir}/lsstsw-fake"
    def fakeManifestFile  = "${fakeLsstswDir}/build/manifest.txt"
    def fakeReposFile     = "${fakeLsstswDir}/etc/repos.yaml"
    def ciDir             = "${baseDir}/ci-scripts"

    docker.image(p.dockerImage).pull()
    def labels = util.shJson """
      docker inspect --format '{{json .Config.Labels }}' ${p.dockerImage}
    """

    if (!labels.VERSIONDB_MANIFEST_ID) {
      missingLabel 'VERSIONDB_MANIFEST_ID'
    }
    if (!labels.LSST_COMPILER) {
      missingLabel 'LSST_COMPILER'
    }

    String manifestId   = labels.VERSIONDB_MANIFEST_ID
    String lsstCompiler = labels.LSST_COMPILER

    try {
      dir(baseDir) {
        // empty ephemeral dirs at start of build
        util.emptyDirs([
          archiveDir,
          datasetArchiveDir,
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

        dir(ciDir) {
          util.cloneCiScripts()
        }

        // clone dataset
        dir(datasetDir) {
          timeout(time: ds.data.clone_timelimit, unit: 'MINUTES') {
            util.checkoutLFS(
              githubSlug: ds.data.github_repo,
              gitRef: ds.data.git_ref,
            )
          } // timeout
        } // dir

        // clone and build from source
        // XXX make this conditional on if we're going to build from source
        dir(codeDir) {
          timeout(time: ds.code.clone_timelimit, unit: 'MINUTES') {
            // the simplier git step doesn't support 'CleanBeforeCheckout'
            def codeRepoUrl = util.githubSlugToUrl(ds.code.github_repo)
            def codeRef     = ds.code.git_ref

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
        ) {

          /*
          // XXX disable build from source for testing
          // XXX make this conditional on if we're going to build from source
          dir(codeDir) {
            // XXX DM-12663 validate_drp must be built from source / be
            // writable by the jenkins role user -- the version installed in
            // the container image can not be used.
            buildDrp(
              homeDir,
              codeDir,
              runSlug,
              ciDir,
              lsstCompiler
            )
          } // dir
          */

          runDrp(
            runDir: runDir,
            //codeDir: codeDir,
            datasetName: ds.name,
            datasetDir: datasetDir,
            datasetArchiveDir: datasetArchiveDir,
          )

          // push results to squash, verify version
          runDispatchqa(
            runDir: runDir,
            //codeDir: codeDir,
            archiveDir: datasetArchiveDir,
            lsstswDir: fakeLsstswDir,
            datasetSlug: ds.display_name,
            noPush: p.noPush
          )
        } // inside
      } // dir
    } finally {
      // collect artifacats
      // note that this should be run relative to the origin workspace path so
      // that artifacts from parallel branches do not collide.
      record(archiveDir, codeDir, fakeLsstswDir)
    }
  } // run

  // retrying is important as there is a good chance that the dataset will
  // fill the disk up
  retry(ds.retries) {
    try {
      node('docker') {
        timeout(time: ds.run_timelimit, unit: 'MINUTES') {
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
 *  Record logs
 *
 * @param archiveDir String path to drp output products that should be
 * persisted
 * @param codeDir String path to validate_drp build dir from which to collect
 * build time logs and/or junit output.
 */
def void record(String archiveDir, String codeDir, String lsstswDir) {
  def archive = [
    "${archiveDir}/**/*",
    "${codeDir}/**/*.log",
    "${codeDir}/**/*.failed",
    "${codeDir}/**/pytest-*.xml",
    "${lsstswDir}/**/*",
  ]

  def reports = [
    "${codeDir}/**/pytest-*.xml",
  ]

  // convert to relative paths
  // https://gist.github.com/ysb33r/5804364
  def rootDir = new File(pwd())
  archive = archive.collect { it ->
    def fullPath = new File(it)
    rootDir.toPath().relativize(fullPath.toPath()).toFile().toString()
  }

  reports = reports.collect { it ->
    def fullPath = new File(it)
    rootDir.toPath().relativize(fullPath.toPath()).toFile().toString()
  }

  archiveArtifacts([
    artifacts: archive.join(', '),
    excludes: '**/*.dummy',
    allowEmptyArchive: true,
    fingerprint: true
  ])

  junit([
    testResults: reports.join(', '),
    allowEmptyResults: true,
  ])
} // record

/**
 * Build validate_drp
 *
 * @param homemDir String path to $HOME -- where to put dotfiles
 * @param codeDir String path to validate_drp (code)
 * @param runSlug String short name to describe this drp run
 */
def void buildDrp(
  String homeDir,
  String codeDir,
  String runSlug,
  String ciDir,
  String lsstCompiler
) {
  // keep eups from polluting the jenkins role user dotfiles
  withEnv([
    "HOME=${homeDir}",
    "EUPS_USERDATA=${homeDir}/.eups_userdata",
    "CODE_DIR=${codeDir}",
    "CI_DIR=${ciDir}",
    "LSST_JUNIT_PREFIX=${runSlug}",
    "LSST_COMPILER=${lsstCompiler}",
  ]) {
    util.bash '''
      cd "$CODE_DIR"

      SHOPTS=$(set +o)
      set +o xtrace

      source "${CI_DIR}/ccutils.sh"
      cc::setup_first "$LSST_COMPILER"

      source /opt/lsst/software/stack/loadLSST.bash
      setup -k -r .

      eval "$SHOPTS"

      scons
    '''
  } // withEnv
}

/**
 * XXX this monster should be moved into an external shell script.
 *
 * Run validate_drp driver script.
 *
 * @param p Map
 * @param p.codeDir String path to validate_drp (code)
 * @param p.runDir String runtime cwd for validate_drp
 * @param p.dataset String full name of the validation dataset
 * @param p.datasetDir String path to validation dataset
 * @param p.datasetArchiveDir String path to persist valildation output products
 */
def void runDrp(Map p) {
  util.requireMapKeys(p, [
    'runDir',
    'datasetName',
    'datasetDir',
    'datasetArchiveDir',
  ])

  p = [codeDir: ''] + p

  // run drp driver script
  def run = {
    util.bash '''
      #!/bin/bash -e

      [[ $JENKINS_DEBUG == true ]] && set -o xtrace

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

      cd "$RUN_DIR"

      # do not xtrace (if set) into loadLSST.bash to avoid bloating the
      # jenkins console log
      SHOPTS=$(set +o)
      set +o xtrace
      source /opt/lsst/software/stack/loadLSST.bash
      eval "$SHOPTS"

      # if CODE_DIR is defined, set that up instead of the default validate_drp
      # product
      if [[ -n $CODE_DIR ]]; then
        setup -k -r "$CODE_DIR"
      else
        setup validate_drp
      fi

      setup -k -r "$DATASET_DIR"

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

      # archive drp processing results
      # process artifacts *before* bailing out if the drp run failed
      mkdir -p "$DATASET_ARCHIVE_DIR"
      artifacts=( "${RESULTS[@]}" "${LOGS[@]}" )

      for r in "${artifacts[@]}"; do
        dest="${DATASET_ARCHIVE_DIR}/${r##*/}"
        # file may not exist due to an error
        if [[ ! -e "${RUN_DIR}/${r}" ]]; then
          continue
        fi
        if ! cp "${RUN_DIR}/${r}" "$dest"; then
          continue
        fi
        # compressing an example hsc output file
        # (cmd)       (ratio)  (time)
        # xz -T0      0.183    0:20
        # xz -T0 -9   0.180    1:23
        # xz -T0 -9e  0.179    1:28
        xz -T0 -9ev "$dest"
      done

      # bail out if the drp output file is missing
      if [[ ! -e  "${RUN_DIR}/${RESULTS[0]}" ]]; then
        echo "drp result file does not exist: ${RUN_DIR}/${RESULTS[0]}"
        exit 1
      fi

      # XXX we are currently only submitting one filter per dataset
      ln -sf "${RUN_DIR}/${RESULTS[0]}" "${RUN_DIR}/output.json"

      exit $run_status
    '''
  } // run

  withEnv([
    "CODE_DIR=${p.codeDir}",
    "RUN_DIR=${p.runDir}",
    "DATASET=${p.datasetName}",
    "DATASET_DIR=${p.datasetDir}",
    "DATASET_ARCHIVE_DIR=${p.datasetArchiveDir}",
    "JENKINS_DEBUG=true",
  ]) {
    run()
  }
}

/**
 * push DRP results to squash using dispatch-verify.
 *
 * @param p Map
 * @param p.resultPath
 * @param p.lsstswDir String Path to (the fake) lsstsw dir
 * @param p.datasetSlug String The dataset "short" name.  Eg., cfht instead of
 * validation_data_cfht.
 * @param p.noPush Boolean if true, do not attempt to push data to squash.
 * Reguardless of that value, the output of the characterization report is recorded
 */
def void runDispatchqa(Map p) {
  util.requireMapKeys(p, [
    'runDir',
    'archiveDir',
    'lsstswDir',
    'datasetSlug',
    'noPush',
  ])

  p = [codeDir: ''] + p

  def run = {
    util.bash '''
      source /opt/lsst/software/stack/loadLSST.bash
      # if CODE_DIR is defined, set that up instead of the default validate_drp
      # product
      if [[ -n $CODE_DIR ]]; then
        setup -k -r "$CODE_DIR"
      else
        setup validate_drp
      fi

      cd "$RUN_DIR"

      # compute characterization report
      reportPerformance.py \
        --output_file="$dataset"_char_report.rst \
        *_output_*.json
      cp "$dataset"_char_report.rst "$ARCH_DIR"
      xz -T0 -9ev "$ARCH_DIR"/"$dataset"_char_report.rst
    '''

    if (!p.noPush) {
      util.bash '''
        source /opt/lsst/software/stack/loadLSST.bash
        # if CODE_DIR is defined, set that up instead of the default validate_drp
        # product
        if [[ -n $CODE_DIR ]]; then
          setup -k -r "$CODE_DIR"
        else
          setup validate_drp
        fi

        cd "$RUN_DIR"

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
    "RUN_DIR=${p.runDir}",
    "CODE_DIR=${p.codeDir}",
    "ARCH_DIR=${p.archiveDir}",
    "NO_PUSH=${p.noPush}",
    "dataset=${p.datasetSlug}",
    "SQUASH_URL=${sqre.squash.url}",
  ]) {
    withCredentials([[
      $class: 'UsernamePasswordMultiBinding',
      credentialsId: 'squash-api-user',
      usernameVariable: 'SQUASH_USER',
      passwordVariable: 'SQUASH_PASS',
    ]]) {
      run()
    } // withCredentials
  } // withEnv
}

def void missingLabel(String label) {
  error "docker ${label} label is missing"
}
