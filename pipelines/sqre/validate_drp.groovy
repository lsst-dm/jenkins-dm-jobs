import groovy.transform.Field

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

@Field String repos_url = 'https://raw.githubusercontent.com/lsst/repos/master/etc/repos.yaml'
@Field String manifest_base_url = 'https://raw.githubusercontent.com/lsst/versiondb/master/manifests'

notify.wrap {
  def required = [
    'EUPS_TAG',
    'BNNNN',
    'COMPILER',
  ]

  util.requireParams(required)

  def masterRetries     = 3
  def verifyPortRetries = 1

  def matrix = [
    cfht: {
      drp('cfht', 'master', true, masterRetries, 1)
    },
    cfht_verify_port: {
      drp('cfht', 'verify_port', false, verifyPortRetries, 1)
    },
    hsc: {
      drp('hsc', 'master', true, masterRetries, 15)
    },
    hsc_verify_port: {
      drp('hsc', 'verify_port', false, verifyPortRetries, 15)
    },
  ]

  stage('matrix') {
    parallel(matrix)
  }
} // notify.wrap

/**
 * Prepare, execute, and record results of a validation_drp run.
 *
 * @param datasetSlug String short name of dataset
 * @param drpRef String validate_drp git repo ref. Defaults to 'master'
 * @param doPostqa Boolean Enables/disables running of post-qa. Defaults to
 * @param retries Integer Number of times to retry after a failure
 * @param timelimit Integer Maximum number of hours per 'try'
 * 'true'
 */
def void drp(
  String datasetSlug,
  String drpRef = 'master',
  Boolean doPostqa = true,
  Integer retries = 3,
  Integer timelimit = 12
) {
  def eupsTag   = params.EUPS_TAG
  def buildId   = params.BNNNN
  def noPush    = params.NO_PUSH
  def compiler  = params.COMPILER

  def datasetInfo  = datasetLookup(datasetSlug)
  def docImage     = "lsstsqre/centos:7-stack-lsst_distrib-${eupsTag}"
  def drpRepo      = 'https://github.com/lsst/validate_drp.git'
  def postqaVer    = '1.3.3'
  def jenkinsDebug = 'true'

  def run = { runSlug ->
    def baseDir           = "${pwd()}/${runSlug}"
    def drpDir            = "${baseDir}/validate_drp"
    def datasetDir        = "${baseDir}/${datasetInfo['dataset']}"
    def homeDir           = "${baseDir}/home"
    def runDir            = "${baseDir}/run"
    def archiveDir        = "${baseDir}/archive"
    def datasetArchiveDir = "${archiveDir}/${datasetInfo['dataset']}"
    def fakeLsstswDir     = "${baseDir}/lsstsw-fake"
    def fakeManifestFile  = "${fakeLsstswDir}/build/manifest.txt"
    def fakeReposFile     = "${fakeLsstswDir}/etc/repos.yaml"
    def postqaDir         = "${archiveDir}/postqa"
    def ciDir             = "${baseDir}/ci-scripts"

    try {
      dir(baseDir) {
        // empty ephemeral dirs at start of build
        util.emptyDirs([
          archiveDir,
          datasetArchiveDir,
          "${fakeLsstswDir}/build",
          "${fakeLsstswDir}/etc",
          postqaDir,
          homeDir,
          runDir,
        ])

        // stage manifest.txt early so we don't risk a long processing run and
        // then fail setting up to run post-qa
        // testing
        downloadManifest(fakeManifestFile, buildId)
        downloadRepos(fakeReposFile)

        dir(ciDir) {
          util.cloneCiScripts()
        }

        // clone validation dataset
        dir(datasetDir) {
          timeout(time: datasetInfo['cloneTime'], unit: 'MINUTES') {
            checkoutLFS(datasetInfo['datasetRepo'], datasetInfo['datasetRef'])
          }
        }

        util.insideWrap(docImage) {
          // clone and build validate_drp from source
          dir(drpDir) {
            // the simplier git step doesn't support 'CleanBeforeCheckout'
            timeout(time: 15, unit: 'MINUTES') {
              checkout(
                scm: [
                  $class: 'GitSCM',
                  branches: [[name: "*/${drpRef}"]],
                  doGenerateSubmoduleConfigurations: false,
                  extensions: [[$class: 'CleanBeforeCheckout']],
                  submoduleCfg: [],
                  userRemoteConfigs: [[url: drpRepo]]
                ],
                changelog: false,
                poll: false,
              )
            } // timeout

            // XXX DM-12663 validate_drp must be built from source / be
            // writable by the jenkins role user -- the version installed in
            // the container image can not be used.
            buildDrp(
              homeDir,
              drpDir,
              runSlug,
              ciDir,
              compiler
            )
          } // dir

          timeout(time: datasetInfo['runTime'], unit: 'MINUTES') {
            runDrp(
              drpDir,
              runDir,
              datasetInfo['dataset'],
              datasetDir,
              datasetArchiveDir
            )
          } // timeout
        } // inside

        // push results to squash
        if (doPostqa) {
          runPostqa(
            "${runDir}/output.json",
            fakeLsstswDir,
            postqaVer,
            "${postqaDir}/post-qa.json",
            datasetSlug,
            // docImage, // XXX DM-12669
            '0xdeadbeef',
            noPush
          )
        }
      } // dir
    } finally {
      // collect artifacats
      // note that this should be run relative to the origin workspace path so
      // that artifacts from parallel branches do not collide.
      record(archiveDir, drpDir, fakeLsstswDir)
    }
  } // run

  // retrying is important as there is a good chance that the dataset will
  // fill the disk up
  retry(retries) {
    try {
      node('docker') {
        timeout(time: timelimit, unit: 'HOURS') {
          if (params.WIPEOUT) {
            deleteDir()
          }

          // create a unique sub-workspace for each parallel branch
          def runSlug = datasetSlug
          if (drpRef != 'master') {
            runSlug += "-" + drpRef.tr('.', '_')
          }

          run(runSlug)
        } // timeout
      } // node
    } catch(e) {
      runNodeCleanup()
      throw e
    }
  } // retry
} // drp

/**
 * Trigger jenkins-node-cleanup (disk space) and wait for it to complete.
 */
def void runNodeCleanup() {
  build job: 'sqre/infrastructure/jenkins-node-cleanup',
    wait: true
}

/**
 * XXX this type of configuration data probably should be in an external config
 * file rather than mixed with code.
 *
 *  Lookup dataset details ("full" name / repo / ref)
 *
 * @param datasetSlug String short name of dataset
 * @return Map of dataset specific details
 */
def Map datasetLookup(String datasetSlug) {
  def info = [:]
  info['datasetSlug'] = datasetSlug

  // all of this information could presnetly be computed heuristically -- but
  // perhaps this won't be the case in the future?
  switch(datasetSlug) {
    case 'cfht':
      info['dataset']     = 'validation_data_cfht'
      info['datasetRepo'] = 'https://github.com/lsst/validation_data_cfht.git'
      info['datasetRef']  = 'master'
      info['cloneTime']   = 15
      info['runTime']     = 15
      break
    case 'hsc':
      info['dataset']     = 'validation_data_hsc'
      info['datasetRepo'] = 'https://github.com/lsst/validation_data_hsc.git'
      info['datasetRef']  = 'master'
      info['cloneTime']   = 240
      info['runTime']     = 600
      break
    case 'decam':
      info['dataset']     = 'validation_data_decam'
      info['datasetRepo'] = 'https://github.com/lsst/validation_data_decam.git'
      info['datasetRef']  = 'master'
      info['cloneTime']   = 60 // XXX untested
      info['runTime']     = 60 // XXX untested
      break
    default:
      error "unknown datasetSlug: ${datasetSlug}"
  }

  return info
}

/**
 *  Record logs
 *
 * @param archiveDir String path to drp output products that should be
 * persisted
 * @param drpDir String path to validate_drp build dir from which to collect
 * build time logs and/or junit output.
 */
def void record(String archiveDir, String drpDir, String lsstswDir) {
  def archive = [
    "${archiveDir}/**/*",
    "${drpDir}/**/*.log",
    "${drpDir}/**/*.failed",
    "${drpDir}/**/pytest-*.xml",
    "${lsstswDir}/**/*",
  ]

  def reports = [
    "${drpDir}/**/pytest-*.xml",
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
 * Create/update a clone of an lfs enabled git repo.
 *
 * @param gitRepo String URL of git repo to clone
 * @param gitRef String git ref to checkout
 */
def void checkoutLFS(String gitRepo, String gitRef = 'master') {
  def docRepo = 'lsstsqre/gitlfs'

  // running a git clone in a docker.inside block is broken
  git([
    url: gitRepo,
    branch: gitRef,
    changelog: false,
    poll: false
  ])

  try {
    util.insideWrap(docRepo) {
      util.bash('git lfs pull origin')
    }
  } finally {
    // try not to break jenkins clone mangement
    util.bash 'rm -f .git/hooks/post-checkout'
  }
} // checkoutLFS

/**
 * Build validate_drp
 *
 * @param homemDir String path to $HOME -- where to put dotfiles
 * @param drpDir String path to validate_drp (code)
 * @param runSlug String short name to describe this drp run
 */
def void buildDrp(
  String homeDir,
  String drpDir,
  String runSlug,
  String ciDir,
  String compiler
) {
  // keep eups from polluting the jenkins role user dotfiles
  withEnv([
    "HOME=${homeDir}",
    "EUPS_USERDATA=${homeDir}/.eups_userdata",
    "DRP_DIR=${drpDir}",
    "CI_DIR=${ciDir}",
    "LSST_JUNIT_PREFIX=${runSlug}",
    "LSST_COMPILER=${compiler}",
  ]) {
    util.bash '''
      cd "$DRP_DIR"

      SHOPTS=$(set +o)
      set +o xtrace

      source "${CI_DIR}/ccutils.sh"
      cc::setup "$LSST_COMPILER"

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
 * @param drpDir String path to validate_drp (code)
 * @param runDir String runtime cwd for validate_drp
 * @param dataset String full name of the validation dataset
 * @param datasetDir String path to validation dataset
 * @param datasetArhiveDir String path to persist valildation output products
 */
def void runDrp(
  String drpDir,
  String runDir,
  String dataset,
  String datasetDir,
  String datasetArchiveDir
) {
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
      setup -k -r "$DRP_DIR"
      setup -k -r "$DATASET_DIR"
      eval "$SHOPTS"

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
    "DRP_DIR=${drpDir}",
    "RUN_DIR=${runDir}",
    "DATASET=${dataset}",
    "DATASET_DIR=${datasetDir}",
    "DATASET_ARCHIVE_DIR=${datasetArchiveDir}",
    "JENKINS_DEBUG=true",
  ]) {
    run()
  }
}

/**
 * push DRP results to squash using post-qa.
 *
 * @param resultFile String DRP output JSON file
 * @param lsstswDir String Path to (the fake) lsstsw dir
 * @param postqaVer String PYPI version of post-qa package to use
 * @param outputFile String A copy of the post-qa json sent to a squash
 * instance.
 * @param datasetSlug String The dataset "short" name.  Eg., cfht instead of
 * validation_data_cfht.
 * @param label String The execution environemnt.  In the matrix job
 * incarnation, this was the jenkins agent (node) label.  Eg., centos-7
 * However, it now probably makes more sense to set this to the docker image
 * name.
 * @param noPush Boolean if true, do not attempt to push data to squash.
 * Reguardless of that value, the output of a post-qa "dry run" is writen to
 * outputFile.
 */
def void runPostqa(
  String resultFile,
  String lsstswDir,
  String postqaVer,
  String outputFile,
  String datasetSlug,
  String label,
  Boolean noPush = true
) {
  def docImage = "lsstsqre/postqa:${postqaVer}"

  def run = {
    util.bash '''
      # archive post-qa output
      # XXX --api-url, --api-user, and --api-password are required even
      # when --test is set
      post-qa \
        --lsstsw "$LSSTSW_DIR" \
        --qa-json "$RESULTFILE" \
        --api-url "$SQUASH_URL"  \
        --api-user "$SQUASH_USER" \
        --api-password "$SQUASH_PASS" \
        --no-probe-git \
        --test \
        > "$OUTPUTFILE"
      xz -T0 -9ev "$OUTPUTFILE"
    '''

    if (!noPush) {
      util.bash '''
        # submit post-qa
        post-qa \
          --lsstsw "$LSSTSW_DIR" \
          --qa-json "$RESULTFILE" \
          --api-url "$SQUASH_URL" \
          --api-user "$SQUASH_USER" \
          --api-password "$SQUASH_PASS" \
          --no-probe-git
      '''
    }
  } // run

  /*
  These env vars are expected by post-qa:
  postqa/jenkinsenv.py
  26:            'ci_id': os.getenv('BUILD_ID', 'demo'),
  27:            'ci_name': os.getenv('PRODUCT', 'demo'),
  28:            'ci_dataset': os.getenv('dataset', 'demo'),
  29:            'ci_label': os.getenv('label', 'demo'),
  30:            'ci_url': os.getenv('BUILD_URL', 'https://example.com'),

  These are already present under pipeline:
  - BUILD_ID
  - BUILD_URL

  This vars were defined automagically by matrixJob and now must be manually
  set:
  - PRODUCT
  - dataset
  - label
  */
  withEnv([
    "RESULTFILE=${resultFile}",
    "LSSTSW_DIR=${lsstswDir}",
    "OUTPUTFILE=${outputFile}",
    "NO_PUSH=${noPush}",
    "PRODUCT=validate_drp",
    "dataset=${datasetSlug}",
    "label=${label}",
  ]) {
    withCredentials([[
      $class: 'UsernamePasswordMultiBinding',
      credentialsId: 'squash-api-user',
      usernameVariable: 'SQUASH_USER',
      passwordVariable: 'SQUASH_PASS',
    ],
    [
      $class: 'StringBinding',
      credentialsId: 'squash-api-url',
      variable: 'SQUASH_URL',
    ]]) {
      util.insideWrap(docImage) {
        run()
      }
    } // withCredentials
  } // withEnv
}

/**
 * Download URL resource and write it to disk.
 *
 * @param url String URL to fetch
 * @param destFile String path to write downloaded file
 */
def void downloadFile(String url, String destFile) {
  url = new URL(url)
  writeFile(file: destFile, text: url.getText())
}

/**
 * Download `manifest.txt` from `lsst/versiondb`.
 *
 * @param destFile String path to write downloaded file
 * @param bxxxx String manifest build id aka bNNNN
 */
def void downloadManifest(String destFile, String bxxxx) {
  def url = "${manifest_base_url}/${bxxxx}.txt"
  downloadFile(url, destFile)
}

/**
 * Download a copy of `repos.yaml`
 *
 * @param destFile String path to write downloaded file
 */
def void downloadRepos(String destFile) {
  downloadFile(repos_url, destFile)
}
