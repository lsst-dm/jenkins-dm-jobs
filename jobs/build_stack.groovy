import util.Common
Common.makeFolders(this)

def folder = 'release/docker'

pipelineJob("${folder}/build-stack") {
  description('Constructs docker images with EUPS tarballs.')

  parameters {
    stringParam('PRODUCT', 'lsst_distrib', 'Whitespace delimited list of EUPS products to build.')
    stringParam('TAG', null, 'EUPS distrib tag name. Eg. w_2016_08')
    stringParam('TIMEOUT', '1', 'build timeout in hours')
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
      scriptPath("pipelines/${folder}/build_stack.groovy")
    }
  }
}
