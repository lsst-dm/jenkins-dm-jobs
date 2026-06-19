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

  util.nodeWrap('linux-64') {
    timeout(time: 4, unit: 'HOURS') {
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
    } // timeout
  } // nodeWrap
} // notify.wrap
