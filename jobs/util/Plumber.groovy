package util

import javaposse.jobdsl.dsl.DslFactory
import javaposse.jobdsl.dsl.Job
import util.Common

class Plumber {
  String name
  String script
  DslFactory dsl

  def script() {
    if (!script) {
      return "pipelines/${name.tr('-', '_')}.groovy"
    }
    script
  }

  Job pipeline() {
    Common.makeFolders(dsl)

    dsl.pipelineJob(name) {
      label('jenkins-master')
      keepDependencies()
      concurrentBuild(false)

      def repo = dsl.SEED_JOB.scm.userRemoteConfigs.get(0).getUrl()
      def ref  = dsl.SEED_JOB.scm.getBranches().get(0).getName()

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
          scriptPath(script())
        }
      } // definition
    } // pipelineJob
  } // pipeline
} // class
