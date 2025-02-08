node('jenkins-manager') {
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
    scipipe = util.scipipeConfig() // needed for side effects
    sqre = util.sqreConfig() // needed for side effects
  }
}

notify.wrap {
  util.requireParams([
    'LATEST',
    'NO_PUSH',
  ])

  Boolean pushLatest = params.LATEST
  Boolean pushDocker = (! params.NO_PUSH.toBoolean())

  def hubRepo    = 'lsstsqre/sqre-github-snapshot'
  def githubRepo = 'lsst-sqre/sqre-git-snapshot'
  def gitRef     = 'refs/tags/0.2.1'
  def hubTag     = tagBasename(gitRef)

  def image = null

  def run = {
    def abbrHash = null

    stage('checkout') {
      checkout([
        $class: 'GitSCM',
        userRemoteConfigs: [[url: "https://github.com/${githubRepo}"]],
        branches: [[name: gitRef]],
        poll: false
      ])

      abbrHash = sh([
        returnStdout: true,
        script: "git log -n 1 --pretty=format:'%h'",
      ]).trim()
    }

    stage('build') {
      dir('docker') {
        // ensure base image is always up to date
        image = docker.build(hubRepo, "--pull=true --no-cache .")
      }
    }

    stage('push') {
      if (pushDocker) {
        docker.withRegistry(
          'https://index.docker.io/v1/',
          'dockerhub-sqreadmin'
        ) {
          image.push(hubTag)
          if (hubTag== 'master') {
            image.push("g${abbrHash}")
          }
          if (pushLatest) {
            image.push('latest')
          }
        }
      }
    } // push
  } // run

  util.nodeWrap('linux-64') {
    timeout(time: 30, unit: 'MINUTES') {
      run()
    }
  }
} // notify.wrap

@NonCPS
def String tagBasename(String ref) {
  // docker tags may not include slashes, so mangle explicit tag refs back to
  // <tagName>
  def m = ref =~ '^refs/tags/(.*)'
  if (m) {
    return m[0][1]
  }

  return ref
}
