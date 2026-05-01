node('jenkins-manager') {
  dir('jenkins-dm-jobs') {
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs(),
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
    'SPLENV_REF',
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

  def splenvRef       = params.SPLENV_REF
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
        dir(buildDir) {
          withCredentials([
            usernamePassword(credentialsId: 'rubinobs-dm',
              usernameVariable: 'GHCR_USER', passwordVariable: 'GHCR_TOKEN'),
          ]) {
            container('kaniko') {
              sh """
                mkdir -p /kaniko/.docker
                printf '%s' '{"auths":{"ghcr.io":{"auth":"'"\$(printf '%s:%s' "\$GHCR_USER" "\$GHCR_TOKEN" | base64 -w0)"'"}}}' \
                  > /kaniko/.docker/config.json
              """

              if (!noPush) {
                sh """
                  /kaniko/executor \
                    --context=. \
                    --dockerfile=Dockerfile \
                    --no-cache \
                    --build-arg LSST_SPLENV_REF="${splenvRef}" \
                    --destination=${dockerRepo}:${dockerTag} \
                    --digest-file=/tmp/digest-newinstall.txt
                """
                def digest = readFile('/tmp/digest-newinstall.txt').trim()
                dockerdigest.add("${dockerRepo}@${digest}")
              } else {
                sh """
                  /kaniko/executor \
                    --context=. \
                    --dockerfile=Dockerfile \
                    --no-cache \
                    --build-arg LSST_SPLENV_REF="${splenvRef}" \
                    --no-push
                """
                dockerdigest.add(null)
              }
            }
          }
        }
      }
    } // run

  util.nodeWrap(lsstswConfig.label) {
    timeout(time: 4, unit: 'HOURS') {
      run()
    }
  } // util.nodeWrap
  }
  }
  parallel matrix

  def merge = {
    stage('digest'){
      if (!noPush) {
        def digests = dockerdigest.findAll { it != null }.join(' ')
        withCredentials([
          usernamePassword(credentialsId: 'rubinobs-dm',
            usernameVariable: 'GHCR_USER', passwordVariable: 'GHCR_TOKEN'),
        ]) {
          container('crane') {
            sh "crane auth login ghcr.io --username \$GHCR_USER --password \$GHCR_TOKEN"
            registryTags.each { name ->
              sh "crane index append -t ${dockerRepo}:${name} ${digests}"
            }
          }
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
