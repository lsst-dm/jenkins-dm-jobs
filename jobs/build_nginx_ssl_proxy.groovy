import util.Plumber

def p = new Plumber(
  name: 'sqre/infra/build-nginx-ssl-proxy',
  dsl: this
)
p.pipeline().with {
  description(
    '''
    Constructs docker lsstsqre/nginx-ssl-proxy image, which is a an updated
    build of gcr.io/cloud-solutions-images/nginx-ssl-proxy.
    '''.replaceFirst("\n","").stripIndent()
  )

  parameters {
    booleanParam('PUSH', true, 'Push container to docker hub.')
  }
}
