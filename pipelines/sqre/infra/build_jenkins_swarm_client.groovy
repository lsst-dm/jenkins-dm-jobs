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
    'SWARM_VER',
    'LATEST',
    'NO_PUSH',
  ])

  String ver         = params.SWARM_VER
  Boolean pushLatest = params.LATEST
  Boolean pushDocker = (! params.NO_PUSH.toBoolean())

  def swarm           = sqre.jenkins_swarm_client
  def dockerfile      = swarm.dockerfile
  def dockerRegistry  = swarm.docker_registry

  def githubRepo = util.githubSlugToUrl(dockerfile.github_repo)
  def gitRef     = dockerfile.git_ref
  def buildDir   = dockerfile.dir
  def dockerRepo = dockerRegistry.repo

  def run = {
    stage('checkout') {
      git([
        url: githubRepo,
        branch: gitRef,
      ])
    }

    // Produce a "generic" image and an "ldfc" image with specific uid/gids.
    stage('build generic') {
      dir(buildDir) {
        withCredentials([
          usernamePassword(credentialsId: 'dockerhub-sqreadmin',
            usernameVariable: 'DH_USER', passwordVariable: 'DH_PASS'),
        ]) {
          container('kaniko') {
            sh """
              mkdir -p /kaniko/.docker
              printf '%s' '{"auths":{"index.docker.io":{"auth":"'"\$(printf '%s:%s' "\$DH_USER" "\$DH_PASS" | base64 -w0)"'"}}}' \
                > /kaniko/.docker/config.json
            """

            if (pushDocker) {
              sh """
                /kaniko/executor \
                  --context=. \
                  --dockerfile=Dockerfile \
                  --no-cache \
                  --build-arg JSWARM_VERSION=${ver} \
                  --destination=${dockerRepo}:${ver}
              """
              if (pushLatest) {
                sh """
                  /kaniko/executor \
                    --context=. \
                    --dockerfile=Dockerfile \
                    --no-cache \
                    --build-arg JSWARM_VERSION=${ver} \
                    --destination=${dockerRepo}:latest
                """
              }
            } else {
              sh """
                /kaniko/executor \
                  --context=. \
                  --dockerfile=Dockerfile \
                  --no-cache \
                  --build-arg JSWARM_VERSION=${ver} \
                  --no-push
              """
            }
          }
        }
      }
    }

    stage('build ldfc') {
      dir(buildDir) {
        withCredentials([
          usernamePassword(credentialsId: 'dockerhub-sqreadmin',
            usernameVariable: 'DH_USER', passwordVariable: 'DH_PASS'),
        ]) {
          container('kaniko') {
            sh """
              mkdir -p /kaniko/.docker
              printf '%s' '{"auths":{"index.docker.io":{"auth":"'"\$(printf '%s:%s' "\$DH_USER" "\$DH_PASS" | base64 -w0)"'"}}}' \
                > /kaniko/.docker/config.json
            """

            if (pushDocker && pushLatest) {
              sh """
                /kaniko/executor \
                  --context=. \
                  --dockerfile=Dockerfile \
                  --no-cache \
                  --build-arg JSWARM_VERSION=${ver} \
                  --build-arg JSWARM_UID=48435 \
                  --build-arg JSWARM_GID=202 \
                  --destination=${dockerRepo}:${ver}-ldfc
              """
            } else {
              sh """
                /kaniko/executor \
                  --context=. \
                  --dockerfile=Dockerfile \
                  --no-cache \
                  --build-arg JSWARM_VERSION=${ver} \
                  --build-arg JSWARM_UID=48435 \
                  --build-arg JSWARM_GID=202 \
                  --no-push
              """
            }
          }
        }
      }
    }
  } // run

  util.nodeWrap('linux-64') {
    timeout(time: 30, unit: 'MINUTES') {
      run()
    }
  } // util.nodeWrap
} // notify.wrap
