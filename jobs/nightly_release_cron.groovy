import util.Plumber

def p = new Plumber(name: 'release/nightly-release-cron', dsl: this)
p.pipeline().with {
  description('Periodically trigger the DM pipelines/dax "nightly".')

  triggers {
    // run every day EXCEPT on the day of the weekly
    cron('0 0 * * 0-3,5-7')
  }
}
