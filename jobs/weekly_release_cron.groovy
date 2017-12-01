import util.Plumber

def p = new Plumber(name: 'release/weekly-release-cron', dsl: this)
p.pipeline().with {
  description('Periodically trigger the DM pipelines/dax "weekly".')

  triggers {
    cron('0 0 * * 6')
  }
}
