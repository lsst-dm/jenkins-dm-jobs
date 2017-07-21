import util.Common
Common.makeFolders(this)

def folder = 'sqre/infrastructure'

pipelineJob("${folder}/build-jupyterlabdemo") {
  description('Constructs docker jupyterlabdemo images.')

  parameters {
    choiceParam('BTYPE', ['w', 'r'], 'Type of build: release|weekly')
    stringParam('YEAR', null, 'Gregorian calendar year.')
    stringParam('WEEK', null, 'Week of Gregorian calendar year.')
    choiceParam('PYVER', ['3', '2'], 'Python major version')
  }

  properties {
    rebuild {
      autoRebuild()
    }
  }

  label('jenkins-master')
  keepDependencies(true)
  concurrentBuild(false)

  def repo = SEED_JOB.scm.userRemoteConfigs.get(0).getUrl()
  def ref  = SEED_JOB.scm.getBranches().get(0).getName()

  definition {
    cpsScm {
      scm {
        git {
          remote {
            url(repo)
          }
          branch(ref)
        }
      }
      scriptPath("pipelines/${folder}/build_jupyterlabdemo.groovy")
    }
  }
}
