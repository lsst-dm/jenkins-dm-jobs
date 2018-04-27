import util.Common
Common.makeFolders(this)

def j = job('sqre/infrastructure/build-tag-monger-triggers') {
  scm {
    git {
      remote {
        github('lsst-sqre/tag-monger')
      }
      branch('master')
    }
  } // scm

  triggers {
    githubPush()
  }

  steps {
    downstreamParameterized {
      trigger('sqre/infrastructure/build-tag-monger') {}
    }
  }
}

Common.addNotification(j)
