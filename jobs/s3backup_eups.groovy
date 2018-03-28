import util.Plumber

[
  DAILY:   'H 4 * * *',
  WEEKLY:  'H 4 * * 0',
  MONTHLY: 'H 4 1 * *',
].each { type, crontab ->
  def p = new Plumber(
    name: "sqre/backup/s3backup-eups-${type.toLowerCase()}-cron",
    dsl: this
  )
  p.pipeline().with {
    description('Backup eups s3 bucket to s3.')

    parameters {
      stringParam('TYPE', type, "type of backup: 'DAILY','WEEKLY','MONTHLY'")
    }

    triggers {
      cron(crontab)
    }
  }
}
