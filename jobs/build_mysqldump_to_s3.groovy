import util.Plumber

def p = new Plumber(name: 'sqre/backup/build-mysqldump-to-s3', dsl: this)
p.pipeline().with {
  description('Constructs lsstsqre/mysqldump-to-s3 container.')
}
