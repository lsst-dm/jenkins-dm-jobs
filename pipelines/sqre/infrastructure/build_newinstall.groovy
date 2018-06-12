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
    config = util.readYamlFile 'etc/science_pipelines/build_matrix.yaml'
  }
}

notify.wrap {
  util.requireParams([
    'LATEST',
    'NO_PUSH',
  ])

  Boolean pushLatest = params.LATEST
  Boolean pushDocker = (! params.NO_PUSH.toBoolean())

  def newinstall     = config.newinstall
  def dockerfile     = newinstall.dockerfile
  def dockerRegistry = newinstall.docker_registry

  def githubRepo = util.githubSlugToUrl(dockerfile.github_repo, 'https')
  def githubRef  = dockerfile.git_ref
  def buildDir   = dockerfile.dir
  def dockerRepo = dockerRegistry.repo
  def url        = newinstall.url

  def image = null

  def run = {
    def abbrHash = null

    stage('checkout') {
      git([
        url: githubRepo,
        branch: githubRef,
      ])

      abbrHash = sh(returnStdout: true, script: "git log -n 1 --pretty=format:'%h'").trim()
    }

    stage('build') {
      def opt = []
      // ensure base image is always up to date
      opt << '--pull=true'
      opt << '--no-cache'
      opt << "--build-arg NEWINSTALL_URL=\"${url}\""
      withCredentials([[
        $class: 'StringBinding',
        credentialsId: 'eups-url',
        variable: 'EUPS_URL'
      ]]) {
        opt << "--build-arg EUPS_PKGROOT_BASE_URL=\"${EUPS_URL}/stack\""
      }
      opt << '.'

      dir(buildDir) {
        // ensure base image is always up to date
        image = docker.build("${dockerRepo}", opt.join(' '))
      }
    }

    stage('push') {
      if (pushDocker) {
        docker.withRegistry(
          'https://index.docker.io/v1/',
          'dockerhub-sqreadmin'
        ) {
          image.push(util.sanitizeDockerTag(githubRef))
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
  } // node
} // notify.wrap
