import util.Plumber

def p = new Plumber(name: 'sqre/backup/nightly-sqre-github-snapshot', dsl: this)
p.pipeline().with {
  description('Nighlty mirror clones of all public github repositories.')

  triggers {
    cron('23 0 * * * ')
  }
}
