import util.Common
Common.makeFolders(this)

def j = job('sqre/infrastructure/build-newinstall-triggers') {
  def repo = SEED_JOB.scm.userRemoteConfigs.get(0).getUrl()
  def ref  = SEED_JOB.scm.getBranches().get(0).getName()

  multiscm {
    git {
      remote {
        github('lsst/lsst')
        branch('master')
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
  }
}

Common.addNotification(j)
