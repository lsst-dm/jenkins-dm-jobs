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
  node('docker') {
    def gitRepo = 'https://github.com/lsst/validation_data_cfht'
    def gitRef  = 'master'
    def runs    = 5
    def repoDir = 'validation_data_cfht'

    try {
      ['1.5.5', '2.3.4'].each { lfsVer ->
        def hub = "docker.io/lsstsqre/gitlfs:${lfsVer}"
        def local = "${hub}-local"

        wrapContainer(hub, local)
        def image = docker.image(local)

        runs.times {
          dir(repoDir) {
            git([
              url: gitRepo,
              branch: gitRef,
              changelog: false,
              poll: false
            ])

            image.inside("-v ${pwd()}:/results") {
              // make lfs 1.5.5 work...
              util.shColor '''
                git config --local --add credential.helper '!f() { cat > /dev/null; echo username=; echo password=; }; f'
              '''

              util.shColor """
                /usr/bin/time \
                  --format='%e' \
                  --output=/results/lfspull-${lfsVer}.txt \
                  --append \
                  git lfs pull origin
              """
            }

            // cleanup before next iteration
            deleteDir()
          } // dir
        } // times
      } // each
    } finally {
      archiveArtifacts([
        artifacts: "**/lfspull*.txt",
      ])
      deleteDir()
    }
  } // node
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

    util.shColor """
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
