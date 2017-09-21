import util.Common
Common.makeFolders(this)

def folder = 'sqre/infrastructure'

pipelineJob("${folder}/build-jupyterlabdemo") {
  description('Constructs docker jupyterlabdemo images.')

  parameters {
    stringParam('TAG', null, 'eups distrib tag')
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
    stringParam('PYVER', '3', 'Python version')
    stringParam('BASE_IMAGE', 'lsstsqre/centos', 'Base Docker image')
    stringParam('IMAGE_NAME', 'lsstsqre/jld-lab', 'Output image name')
    stringParam('TAG_PREFIX', '7-stack-lsst_distrib-', 'Tag prefix')
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
