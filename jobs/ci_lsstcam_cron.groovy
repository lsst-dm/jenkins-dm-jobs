import util.Plumber

def p = new Plumber(name: 'scipipe/ci-lsstcam-cron', dsl: this)
p.pipeline().with {
  description('Periodically trigger the DM stack-os-matrix with ci_lsstcam "nightly".')

  triggers {
    // run every day at 7 am
    cron('0 7 * * *')
  }
}
