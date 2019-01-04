import util.Common
Common.makeFolders(this)

def j = job('sqre/infra/build-gitlfs-server-triggers') {
  scm {
    git {
      remote {
        github('lsst-sqre/git-lfs-s3-server')
      }
      branch('master')
    }
  } // scm

  triggers {
    githubPush()
  }

  steps {
    downstreamParameterized {
      trigger('sqre/infra/build-gitlfs-server') {}
    }
  }
}

Common.addNotification(j)
