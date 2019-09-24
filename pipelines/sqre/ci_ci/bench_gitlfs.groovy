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
    scipipe = util.scipipeConfig() // needed for side effects
    sqre = util.sqreConfig() // needed for side effects
  }
}

notify.wrap {
  util.requireParams(['LFS_VER', 'RUNS'])

  String lfsVer = params.LFS_VER
  Integer runs  = params.RUNS

  def gitRepo         = 'https://github.com/lsst/validation_data_cfht'
  def gitRef          = 'master'
  def repoDirCached   = 'validation_data_cfht-cached'
  def repoDirLfs      = 'validation_data_cfht-lfs'
  def resultsBasename = 'results'

  def matrix = [:]

  def run = { tag ->
    try {
      def hub             = "lsstsqre/gitlfs:${tag}"
      def local           = "${hub}-local"
      def workDir         = pwd()
      def resultsDir      = "${workDir}/${resultsBasename}"

      wrapDockerImage(hub, local)
      def image = docker.image(local)

      // pre-create results dir
      util.emptyDirs([resultsDir])

      // only hit github once
      dir(repoDirCached) {
        git([
          url: gitRepo,
          branch: gitRef,
          changelog: false,
          poll: false
        ])
      }

      runs.times { n ->
        echo "sample #${n+1}"

        // stage cached git repo
        util.bash "cp -ra ${repoDirCached} ${repoDirLfs}"

        // run lfs
        dir(repoDirLfs) {
          image.inside("-v ${resultsDir}:/results") {
            // make lfs 1.5.5 work...
            util.bash '''
              git config --local --add credential.helper '!f() { cat > /dev/null; echo username=; echo password=; }; f'
            '''

            util.bash """
              /usr/bin/time \
                --format='%e' \
                --output=/results/lfspull-${tag}.txt \
                --append \
                git lfs pull origin
            """
          }

          // cleanup before next iteration
          deleteDir()
        } // dir
      } // times
    } finally {
      archiveArtifacts([
        artifacts: "${resultsBasename}/**/lfspull*.txt",
        excludes: '**/*.dummy',
      ])

      deleteDir()
    }
  } // run

  lfsVer.split().each { tag ->
    matrix[tag] = {
      util.nodeWrap('docker') {
        timeout(time: 48, unit: 'HOURS') {
          run(tag)
        }
      } // util.nodeWrap
    } // matrix
  } // each

  stage('benchmark') {
    parallel matrix
  }
} // notify.wrap

def void wrapDockerImage(String imageName, String tag) {
  def buildDir = 'docker'
  def config = util.dedent("""
    FROM ${imageName}

    ARG     D_USER
    ARG     D_UID
    ARG     D_GROUP
    ARG     D_GID
    ARG     D_HOME

    USER    root
    RUN     groupadd -g \$D_GID \$D_GROUP
    RUN     useradd -d \$D_HOME -g \$D_GROUP -u \$D_UID \$D_USER
    RUN     yum install -y time

    USER    \$D_USER
    WORKDIR \$D_HOME
  """)

  // docker insists on recusrively checking file access under its execution
  // path -- so run it from a dedicated dir
  dir(buildDir) {
    util.buildImage(
      config: config,
      tag: tag,
      pull: true,
    )

    deleteDir()
  }
}
