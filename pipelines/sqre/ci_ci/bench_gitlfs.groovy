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
  util.requireParams(['LFS_VER', 'RUNS'])

  def lfsVer  = params.LFS_VER
  def runs    = params.RUNS.toInteger()

  def gitRepo         = 'https://github.com/lsst/validation_data_cfht'
  def gitRef          = 'master'
  def repoDirCached   = 'validation_data_cfht-cached'
  def repoDirLfs      = 'validation_data_cfht-lfs'
  def resultsBasename = 'results'

  def matrix = [:]

  def run = {
    try {
      def hub             = "lsstsqre/gitlfs:${tag}"
      def local           = "${hub}-local"
      def workDir         = pwd()
      def resultsDir      = "${workDir}/${resultsBasename}"

      wrapContainer(hub, local)
      def image = docker.image(local)

      // pre-create results dir
      dir(resultsDir) {
        writeFile(file: '.dummy', text: '')
      }

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
      node('docker') {
        timeout(time: 48, unit: 'HOURS') {
          run()
        }
      } // node
    } // matrix
  } // each

  stage('benchmark') {
    parallel matrix
  }
} // notify.wrap

def void wrapContainer(String imageName, String tag) {
  def buildDir = 'docker'
  def config = util.dedent("""
    FROM    ${imageName}

    ARG     USER
    ARG     UID
    ARG     GROUP
    ARG     GID
    ARG     HOME

    USER    root
    RUN     groupadd -g \$GID \$GROUP
    RUN     useradd -d \$HOME -g \$GROUP -u \$UID \$USER
    RUN     yum install -y time

    USER    \$USER
    WORKDIR \$HOME
  """)

  // docker insists on recusrively checking file access under its execution
  // path -- so run it from a dedicated dir
  dir(buildDir) {
    writeFile(file: 'Dockerfile', text: config)

    util.bash """
      set -e
      set -x

      docker build -t "${tag}" \
          --build-arg USER="\$(id -un)" \
          --build-arg UID="\$(id -u)" \
          --build-arg GROUP="\$(id -gn)" \
          --build-arg GID="\$(id -g)" \
          --build-arg HOME="\$HOME" \
          .
    """

    deleteDir()
  }
}
