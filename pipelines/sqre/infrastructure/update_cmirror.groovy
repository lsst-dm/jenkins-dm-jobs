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
  def hub_repo = 'lsstsqre/cmirror'
  def retries  = 3

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
      mirror(image.id, 'https://repo.continuum.io/pkgs/main/', platform)
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
      withCmirrorCredentials {
        docker.image(util.defaultAwscliImage()).inside {
          // XXX aws s3 sync appears to give up too easily on error and a
          // failure on a single object will cause the job to fail, so it seems
          // reasonable to retry the entire operation.
          // See: https://github.com/aws/aws-cli/issues/1092
          catchError {
            retry(retries) {
              util.posixSh '''
                aws s3 sync \
                  --only-show-errors \
                  ./local_mirror/ \
                  "s3://${CMIRROR_S3_BUCKET}/pkgs/free/"
              '''
            } // retry
          } // catchError

          catchError {
            retry(retries) {
              util.posixSh '''
                aws s3 sync \
                  --only-show-errors \
                  ./miniconda/ \
                  "s3://${CMIRROR_S3_BUCKET}/miniconda/"
              '''
            } // retry
          } // catchError
        } // .inside
      } // withCmirrorCredentials
    } // stage
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
    runMirror(imageId, upstream, platform)
  }
}

def runMirror(String image, String upstream, String platform) {
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
    "UPSTREAM=${upstream}",
    "PLATFORM=${platform}",
  ]) {
    util.insideDockerWrap(
      image: image,
      pull: true,
    ) {
      util.bash '''
        conda-mirror \
          --temp-directory "$(pwd)/tmp" \
          --num-threads 0 \
          --upstream-channel "$UPSTREAM" \
          --target-directory "$(pwd)/local_mirror" \
          --platform "$PLATFORM" \
          -vvv
      '''
    } // util.insideDockerWrap
  } // withEnv
} // runMirror

/**
 * Run block with "cmirror" credentials defined in env vars.
 *
 * Variables defined:
 * - AWS_ACCESS_KEY_ID
 * - AWS_SECRET_ACCESS_KEY
 * - CMIRROR_S3_BUCKET
 *
 * @param run Closure Invoked inside of node step
 */
def void withCmirrorCredentials(Closure run) {
  withCredentials([[
    $class: 'UsernamePasswordMultiBinding',
    credentialsId: 'aws-cmirror-push',
    usernameVariable: 'AWS_ACCESS_KEY_ID',
    passwordVariable: 'AWS_SECRET_ACCESS_KEY',
  ]]) {
    util.withCondaMirrorEnv {
      run()
    }
  }
}
