import util.Plumber

def p = new Plumber(name: 'sqre/backup/s3backup-eups', dsl: this)
p.pipeline().with {
  description('Backup eups s3 bucket to s3.')

  parameters {
    stringParam('TYPE', 'DAILY', "type of backup: 'DAILY','WEEKLY','MONTHLY'")
  }

  triggers {
    cron('H 4 * * *')
  }
}
