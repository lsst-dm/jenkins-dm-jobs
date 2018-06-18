import groovy.transform.Field

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
    sqre = util.sqreConfig()
  }
}

@Field String wgetImage = 'lsstsqre/wget'

notify.wrap {
  def hub_repo    = 'lsstsqre/cmirror'

  def run = {
    def image = docker.image("${hub_repo}:latest")

    stage('prepare') {
      image.pull()

      util.bash '''
        mkdir -p local_mirror tmp
        chmod 777 local_mirror tmp
      '''

      // cleanup download repodata.json files between builds
      dir('repodata') {
        deleteDir()
      }
    }

    [
      'linux-64',
      'osx-64',
      'noarch',
    ].each { platform ->
      mirror(image.id, 'https://repo.continuum.io/pkgs/free/', platform)
    }

    stage('mirror miniconda') {
      docker.image(wgetImage).inside {
        util.posixSh '''
          wget \
            --mirror \
            --continue \
            --no-parent \
            --no-host-directories \
            --progress=dot:giga \
            -R "*.exe" \
            -R "*ppc64le.sh" \
            -R "*armv7l.sh" \
            -R "*x86.sh" \
            https://repo.continuum.io/miniconda/
        '''
      }
    }

    stage('push to s3') {
      withCredentials([[
        $class: 'UsernamePasswordMultiBinding',
        credentialsId: 'aws-cmirror-push',
        usernameVariable: 'AWS_ACCESS_KEY_ID',
        passwordVariable: 'AWS_SECRET_ACCESS_KEY'
      ],
      [
        $class: 'StringBinding',
        credentialsId: 'cmirror-s3-bucket',
        variable: 'CMIRROR_S3_BUCKET'
      ]]) {
        docker.image(util.defaultAwscliImage()).inside {
          catchError {
            util.posixSh '''
              aws s3 sync \
                --only-show-errors \
                ./local_mirror/ \
                "s3://${CMIRROR_S3_BUCKET}/pkgs/free/"
            '''
          }
          catchError {
            util.posixSh '''
              aws s3 sync \
                --only-show-errors \
                ./miniconda/ \
                "s3://${CMIRROR_S3_BUCKET}/miniconda/"
            '''
          }
        } // .inside
      } // withCredentials
    } // stage('push to s3')
  } // run

  // the timeout should be <= the cron triggering interval to prevent builds
  // pilling up in the backlog.
  timeout(time: 23, unit: 'HOURS') {
    node('docker') {
      // the longest observed runtime is ~6 hours
      timeout(time: 9, unit: 'HOURS') {
        run()
      }
    } // node
  } // timeout
} // notify.wrap

def mirror(String imageId, String upstream, String platform) {
  stage("mirror ${platform}") {
    runMirror(imageId, 'https://repo.continuum.io/pkgs/free/', platform)
  }
}

def runMirror(String imageId, String upstream, String platform) {
  def localImageName = "${imageId}-local"

  util.wrapContainer(imageId, localImageName)

  // archive a copy of the upstream repodata.json at (or as close to as is
  // possible) the time conda-mirror is run.  This may be useful for debugging
  // suspected repodata.json issues as conda-mirror completely rewrites the
  // packages section of this file.
  dir("repodata/${platform}") {
    docker.image(wgetImage).inside {
      util.posixSh "wget ${upstream}${platform}/repodata.json"
    }
  }

  archiveArtifacts([
    artifacts: 'repodata/**/*',
    fingerprint: true,
  ])

  withEnv([
    "IMAGE=${localImageName}",
    "UPSTREAM=${upstream}",
    "PLATFORM=${platform}",
  ]) {
    util.bash '''
      docker run \
        -v $(pwd)/tmp:/tmp \
        -v $(pwd)/local_mirror:/local_mirror \
        "$IMAGE" \
        /miniconda/bin/conda-mirror \
        --num-threads 0 \
        --upstream-channel "$UPSTREAM" \
        --target-directory /local_mirror \
        --platform "$PLATFORM" \
        -vvv
    '''
  }
}
