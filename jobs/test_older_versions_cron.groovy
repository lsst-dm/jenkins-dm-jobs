import util.Plumber

def p = new Plumber(name: 'release/test-older-release-cron', dsl: this)
p.pipeline().with {
  description('Periodically trigger the DM pipelines/dax "test older".')

  triggers {
    cron('0 0 * * 1')
  }
}
