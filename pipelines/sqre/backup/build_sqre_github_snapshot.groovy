node('jenkins-master') {
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

notify.wrap {
  def image      = null
  def hubRepo    = 'lsstsqre/sqre-github-snapshot'
  def githubRepo = 'lsst-sqre/sqre-git-snapshot'
  def githubRef  = 'refs/tags/0.2.1'
  def hubTag     = tagBasename(githubRef)
  def pushLatest = params.LATEST
  def noPush     = params.NO_PUSH


  def run = {
    def abbrHash = null

    stage('checkout') {
      checkout([
        $class: 'GitSCM',
        userRemoteConfigs: [[url: "https://github.com/${githubRepo}"]],
        branches: [[name: githubRef]],
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
        image = docker.build("${hubRepo}", "--pull=true --no-cache .")
      }
    }

    stage('push') {
      if (!noPush) {
        docker.withRegistry(
          'https://index.docker.io/v1/',
          'dockerhub-sqreadmin'
        ) {
          image.push(githubRef)
          if (githubRef == 'master') {
            image.push("g${abbrHash}")
          }
          if (pushLatest) {
            image.push('latest')
          }
        }
      }
    } // push
  } // run

  node('docker') {
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
