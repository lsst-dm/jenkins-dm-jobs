import util.Plumber

def p = new Plumber(name: 'sqre/infra/infra-weekly', dsl: this)
p.pipeline().with {
  description('Periodic builds of infra jobs.')
}
