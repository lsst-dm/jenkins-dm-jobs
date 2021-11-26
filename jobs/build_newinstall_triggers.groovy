import util.Common
Common.makeFolders(this)

def j = job('sqre/infra/build-newinstall-triggers') {
  def repo = SEED_JOB.scm.userRemoteConfigs.get(0).getUrl()
  def ref  = SEED_JOB.scm.getBranches().get(0).getName()

  multiscm {
    git {
      remote {
        github('lsst/lsst')
        branch('main')
      }
    }

    git {
      remote {
        github('lsst-sqre/docker-newinstall')
        branch('master')
      }
    }
  } // scm

  triggers {
    githubPush()

    // it would be slick if we could trigger based on dockerhub lsstsqre/centos
    // notifications but AFAIK, this can't be restricted by tag
    upstream('sqre/infra/build-layercake', 'SUCCESS')
  }

  steps {
    downstreamParameterized {
      trigger('sqre/infra/build-newinstall') {}
    }
  }
}

Common.addNotification(j)
