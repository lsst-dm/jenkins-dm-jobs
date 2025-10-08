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
    'NO_PUSH',
  ])
  def build_stack    = scipipe.build_stack
  def lsstswConfigs  = build_stack.lsstsw_config
  def release        = scipipe.newinstall
  def dockerfile     = release.dockerfile
  def githubRepo     = util.githubSlugToUrl(dockerfile.github_repo)
  def gitRef         = dockerfile.git_ref
  def buildDir       = dockerfile.dir
  def dockerRepo     = release.docker_registry.repo
  def dockerTag      = release.docker_registry.tag
  def dockerdigest   = []

  Boolean noPush         = params.NO_PUSH



  def splenvRef       = lsstswConfigs[0].splenv_ref
  def registryTags = [
    dockerTag,
    "latest",
    "$dockerTag-$splenvRef",
  ]
  def matrix = [:]
  lsstswConfigs.each{ lsstswConfig ->
    def slug = util.lsstswConfigSlug(lsstswConfig)
    matrix[slug] ={
    def run = {
      stage('checkout') {
        repo = git([
          url: githubRepo,
          branch: gitRef,
        ])
      }
      stage('build') {
        def opt = []
        opt << '--pull=true'
        opt << '--no-cache'
        opt << "--build-arg LSST_SPLENV_REF=\"${splenvRef}\""
        opt << "--load"
        opt << '.'
        dir(buildDir) {
          println("TEST")
          println(dockerRepo)
          image = docker.build("${dockerRepo}", opt.join(' '))
        }
      }
      stage('push') {
        def digest = null
        if (!noPush) {
          docker.withRegistry(
            'https://ghcr.io',
            'rubinobs-dm'
          ) {
            registryTags.each { name ->
              image.push(name)
            }
          }
          digest = sh(
            script: "docker inspect --format='{{index .RepoDigests 0}}' ${dockerRepo}:${dockerTag}",
            returnStdout: true
          ).trim()

        }
          dockerdigest.add(digest)
      } // push
  }


  util.nodeWrap(lsstswConfig.label) {
    timeout(time: 4, unit: 'HOURS') {
      run()
    }
  } // util.nodeWrap('linux-64')
  }
  }
  parallel matrix

  def merge = {
    stage('digest'){
        def digest = dockerdigest.join(' ')
        docker.withRegistry(
          'https://ghcr.io',
          'rubinobs-dm'
        ) {
        registryTags.each { name ->
          sh(script: """ \
            docker buildx imagetools create -t $dockerRepo:$name \
            $digest
            """,
            returnStdout: true)
          }
        }
      }
  } // merge
  util.nodeWrap('linux-64') {
      timeout(time: 1, unit: 'HOURS') {
        merge()
      }
    } // nodeWrap

} // notify.wrap


