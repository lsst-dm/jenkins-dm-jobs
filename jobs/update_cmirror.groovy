import util.Plumber

def p = new Plumber(name: 'sqre/infrastructure/update-cmirror', dsl: this)
p.pipeline().with {
  description('Update S3 mirror of conda packages.')
}
