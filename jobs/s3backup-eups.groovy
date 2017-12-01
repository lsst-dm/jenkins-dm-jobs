import util.Plumber

def p = new Plumber(name: 'sqre/backup/s3backup-eups', dsl: this)
p.pipeline().with {
  description('Backup eups s3 bucket to s3.')

  triggers {
    cron('H 4 * * *')
  }
}
