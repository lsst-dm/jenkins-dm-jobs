import util.Plumber

def p = new Plumber(name: 'sqre/infrastructure/infra-weekly', dsl: this)
p.pipeline().with {
  description('Periodic weekly builds of infrastructure jobs.')
}
