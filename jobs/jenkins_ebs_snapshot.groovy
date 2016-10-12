import util.Common

def name = 'ec2-snapshot'
def org = 'lsst-sqre'
def slug = "${org}/${name}"

def j = job('ci-ci/jenkins-ebs-snapshot') {
  scm {
    git {
      remote {
        github(slug)
        //refspec('+refs/pull/*:refs/remotes/origin/pr/*')
      }
      branch('*/master')
      extensions {
        cloneOptions {
          shallow(true)
        }
      }
    }
  }

  properties {
    rebuild {
      autoRebuild()
    }
  }

  // must run on jenkins master node
  label('jenkins-master')

  triggers {
    cron('0 0 * * *')
  }

  wrappers {
    credentialsBinding {
      usernamePassword(
        'AWS_ACCESS_KEY_ID',
        'AWS_SECRET_ACCESS_KEY',
        'jenkins-aws'
      )
    }
  }

  steps {
    shell('sh ec2-snapshot.sh')
  }
}

Common.addNotification(j)
