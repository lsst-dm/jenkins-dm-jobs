import util.Common
Common.makeFolders(this)

def folder = 'sqre/infrastructure'

pipelineJob("${folder}/build-nginx-ssl-proxy") {
  description(
    '''
    Constructs docker lsstsqre/nginx-ssl-proxy image, which is a an updated
    build of gcr.io/cloud-solutions-images/nginx-ssl-proxy.
    '''.replaceFirst("\n","").stripIndent()
  )

  parameters {
    booleanParam('PUSH', true, 'Push container to docker hub.')
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
      scriptPath("pipelines/${folder}/build_nginx_ssl_proxy.groovy")
    }
  }
}
