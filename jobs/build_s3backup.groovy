import util.Plumber

def p = new Plumber(name: 'sqre/backup/build-s3backup', dsl: this)
p.pipeline().with {
  description('Constructs lsstsqre/s3backup container.')
}
