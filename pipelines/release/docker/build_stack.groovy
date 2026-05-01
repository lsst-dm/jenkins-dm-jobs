properties([
  copyArtifactPermission('/release/*'),
]);

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
    'EUPS_TAG',
    'NO_PUSH',
    'PRODUCTS',
    'TIMEOUT',
  ])

  String eupsTag         = params.EUPS_TAG
  String products        = params.PRODUCTS
  Boolean noPush         = params.NO_PUSH
  Integer timelimit      = Integer.parseInt(params.TIMEOUT)
  String extraDockerTags = params.DOCKER_TAGS

  // optional
  String manifestId   = params.MANIFEST_ID ?: ''
  String lsstCompiler = params.LSST_COMPILER ?: ''


  def build_stack    = scipipe.build_stack
  def lsstswConfigs  = build_stack.lsstsw_config
  def release        = scipipe.scipipe_release
  def dockerfile     = release.dockerfile
  def dockerRegistry = release.docker_registry
  def newinstall     = scipipe.newinstall

  def githubRepo     = util.githubSlugToUrl(dockerfile.github_repo)
  def gitRef         = dockerfile.git_ref
  def buildDir       = dockerfile.dir
  def dockerRepo     = dockerRegistry.repo
  def gcpRepo        = dockerRegistry.repo
  def dockerTag      = "al9-${eupsTag}"
  def timestamp      = util.epochMilliToUtc(currentBuild.startTimeInMillis)
  def shebangtronUrl = util.shebangtronUrl()
  def dockerdigest   = []
  def gcpdigest      = []

  if (dockerRegistry.ghcr) {
      dockerRepo = "ghcr.io/${dockerRepo}"
  }
  def registryTags = [
    dockerTag,
    "${dockerTag}-${timestamp}",
  ]

  if (extraDockerTags) {
    // manual constructor is needed "because java"
    registryTags += Arrays.asList(extraDockerTags.split())
    def extraTagList = Arrays.asList(extraDockerTags.split())
    extraTagList.each { tag ->
    registryTags += "al9-${tag}"
    }
  }


  def newRegistryTags = []
  registryTags.each { name ->
    fixOSVersion = name.replaceFirst("7", "9")
    fixDistribName = fixOSVersion.replace("stack-lsst_distrib", "lsst_sitcom")
    newRegistryTags += fixDistribName
  }

  def matrix = [:]
  lsstswConfigs.each{ lsstswConfig ->
    def slug = util.lsstswConfigSlug(lsstswConfig)
    matrix[slug] ={

    def newinstallImage = newinstall.docker_registry.repo
    def newinstallTagBase = newinstall.docker_registry.tag
    def splenvRef       = lsstswConfig.splenv_ref
    if (params.SPLENV_REF) {
      splenvRef = params.SPLENV_REF
    }

    def baseImage       = "${newinstallImage}:${newinstallTagBase}-${splenvRef}"
    def repo  = null

    def run = {
      stage('checkout') {
        repo = git([
          url: githubRepo,
          branch: gitRef,
        ])
      }

      stage('build') {
        def arch = lsstswConfig.display_name.tokenize('-').last()
        def buildArgs = [
          "--build-arg EUPS_PRODUCTS=\"${products}\"",
          "--build-arg EUPS_TAG=\"${eupsTag}\"",
          "--build-arg DOCKERFILE_GIT_BRANCH=\"${repo.GIT_BRANCH}\"",
          "--build-arg DOCKERFILE_GIT_COMMIT=\"${repo.GIT_COMMIT}\"",
          "--build-arg DOCKERFILE_GIT_URL=\"${repo.GIT_URL}\"",
          "--build-arg JENKINS_JOB_NAME=\"${env.JOB_NAME}\"",
          "--build-arg JENKINS_BUILD_ID=\"${env.BUILD_ID}\"",
          "--build-arg JENKINS_BUILD_URL=\"${env.RUN_DISPLAY_URL}\"",
          "--build-arg BASE_IMAGE=\"${baseImage}\"",
          "--build-arg SHEBANGTRON_URL=\"${shebangtronUrl}\"",
          "--build-arg VERSIONDB_MANIFEST_ID=\"${manifestId}\"",
          "--build-arg LSST_COMPILER=\"${lsstCompiler}\"",
          "--build-arg LSST_SPLENV_REF=\"${splenvRef}\"",
        ].join(' ')

        dir(buildDir) {
          withCredentials([
            usernamePassword(credentialsId: 'rubinobs-dm',
              usernameVariable: 'GHCR_USER', passwordVariable: 'GHCR_TOKEN'),
            file(credentialsId: 'google_archive_registry_sa',
              variable: 'GCP_SA_KEY'),
          ]) {
            container('kaniko') {
              def ghcrDigest = null
              def gcpBuildDigest = null

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
                    ${buildArgs} \
                    --destination=${dockerRepo}:${dockerTag}_${arch} \
                    --digest-file=/tmp/digest-ghcr.txt
                """
                ghcrDigest = readFile('/tmp/digest-ghcr.txt').trim()

                sh """
                  /kaniko/executor \
                    --context=. \
                    --dockerfile=Dockerfile \
                    --no-cache \
                    ${buildArgs} \
                    --destination=us-central1-docker.pkg.dev/prompt-proto/${gcpRepo}:${dockerTag}_${arch} \
                    --digest-file=/tmp/digest-gcp.txt \
                    --google-application-credentials=\$GCP_SA_KEY
                """
                gcpBuildDigest = readFile('/tmp/digest-gcp.txt').trim()
              } else {
                sh """
                  /kaniko/executor \
                    --context=. \
                    --dockerfile=Dockerfile \
                    --no-cache \
                    ${buildArgs} \
                    --no-push
                """
              }

              dockerdigest.add(ghcrDigest ? "${dockerRepo}@${ghcrDigest}" : null)
              gcpdigest.add(gcpBuildDigest ? "us-central1-docker.pkg.dev/prompt-proto/${gcpRepo}@${gcpBuildDigest}" : null)
            }
          }
        }
      } // build

      stage('push') {
        // push is handled inline by kaniko --destination; this stage is kept
        // for structural parity and archiving below
      }

  } // run

  util.nodeWrap(lsstswConfig.label) {
    try {
      timeout(time: timelimit, unit: 'HOURS') {
        run()
      }
    } finally {
      stage('archive') {
        def resultsFile = 'results.json'

        util.dumpJson(resultsFile,  [
          base_image: baseImage ?: null,
          image: "${dockerRepo}:${dockerTag}",
          docker_registry: [
            repo: dockerRepo,
            tag: dockerTag
          ],
        ])

        archiveArtifacts([
          artifacts: resultsFile,
          fingerprint: true
        ])
      } // stage
    } // try
  } // util.nodeWrap
  }
  }
  parallel matrix

  def merge = {
    stage('digest'){
      if (!noPush) {
        def ghcrDigests = dockerdigest.findAll { it != null }.join(' ')
        def gcpDigests  = gcpdigest.findAll { it != null }.join(' ')

        withCredentials([
          usernamePassword(credentialsId: 'rubinobs-dm',
            usernameVariable: 'GHCR_USER', passwordVariable: 'GHCR_TOKEN'),
          file(credentialsId: 'google_archive_registry_sa',
            variable: 'GCP_SA_KEY'),
        ]) {
          container('crane') {
            sh "crane auth login ghcr.io --username \$GHCR_USER --password \$GHCR_TOKEN"
            registryTags.each { name ->
              sh "crane index append -t ${dockerRepo}:${name} ${ghcrDigests}"
            }

            sh "crane auth login us-central1-docker.pkg.dev --username _json_key_base64 --password \"\$(base64 -w0 \$GCP_SA_KEY)\""
            registryTags.each { name ->
              sh "crane index append -t us-central1-docker.pkg.dev/prompt-proto/${gcpRepo}:${name} ${gcpDigests}"
            }
          }
        }
      }
    }

  } // merge
  util.nodeWrap('linux-64') {
      timeout(time: timelimit, unit: 'HOURS') {
        merge()
      }
    } // nodeWrap

} // notify.wrap
