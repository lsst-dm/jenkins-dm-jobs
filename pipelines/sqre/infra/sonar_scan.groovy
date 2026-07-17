node('jenkins-manager') {
  dir('jenkins-dm-jobs') {
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs(),
    ])
    notify = load 'pipelines/lib/notify.groovy'
    util = load 'pipelines/lib/util.groovy'
    scipipe = util.scipipeConfig() // side effects
    sqre = util.sqreConfig() // side effects
  }
}

notify.wrap {
  util.requireParams(['EUPS_TAG', 'CACHE_TAG'])

  String eupsTag  = params.EUPS_TAG
  String cacheTag = params.CACHE_TAG

  String envPrefix = env.SONAR_ENV_PREFIX ?: ''

  // The runner is the jnlp-client agent image rather than the scipipe stack
  // image: sonar-scanner needs a JRE (and `which`), which the conda-based
  // scipipe image lacks, whereas the jnlp-client image ships Java, git, and
  // coreutils. loadCache() only needs git (cloneCiScripts, in the runner) plus
  // gcloud (in the sidecar); the scan reads XML/source from the cache and never
  // touches the LSST stack, so it does not need the scipipe image.
  String runnerImage = 'ghcr.io/lsst-dm/jenkins-jnlp-client:latest'

  timeout(time: 4, unit: 'HOURS') {
    // loadCache() runs in the gcloud-cli sidecar; sonarScanWorkspace() runs in
    // the runner container. Both share the pod's /j workspace, so the scan MUST
    // happen in the same pod that downloaded the cache -- a separate pod would
    // not see the extracted lsstsw/build tree.
    util.insideK8sContainer(
      image: runnerImage,
      pull: true,
      cacheImage: util.defaultGcloudCliImage(),
      // Sonar-scan is lightweight compared to a stack build. Requesting a small
      // footprint lets the pod pack onto an already-running node instead of
      // forcing the autoscaler to spin up a fresh (frequently stocked-out) c4d.
      // Limits still allow bursting for the parallel scanners and umbrella scan.
      cpuRequest: '2',
      cpuLimit:   '6',
      memRequest: '8Gi',
      memLimit:   '16Gi',
      storage:    '100Gi',
    ) {
      stage('load cache') {
        // util.loadCache cd's into <buildDir>, clones ci-scripts, runs loadlsststack.sh.
        // After it returns, lsstsw/ is extracted under that buildDir.
        util.loadCache('cache-load', cacheTag)
        // Symlink so subsequent stages can use lsstsw/build/<pkg> from the workspace root.
        sh '''
          if [ -d cache-load/lsstsw ]; then
            ln -sfn cache-load/lsstsw lsstsw
          fi
          test -d lsstsw/build || { echo "lsstsw/build missing after loadCache"; exit 1; }
        '''
      }

      stage('scan') {
        util.sonarScanWorkspace(eupsTag: eupsTag, envPrefix: envPrefix)
      }
    } // insideK8sContainer
  } // timeout
} // notify.wrap
