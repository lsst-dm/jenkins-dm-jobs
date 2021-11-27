import util.Plumber

def p = new Plumber(name: 'sqre/backup/jenkins-ebs-snapshot', dsl: this)
p.pipeline().with {
  description('snapshot jenkins manager ebs volume.')

  triggers {
    cron('0 0 * * *')
  }
}
