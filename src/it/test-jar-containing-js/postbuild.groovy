import java.util.jar.*

File artifact = new File( basedir, "target/sse-gateway-no-version.jar" )
assert artifact.exists()

JarFile jar = new JarFile( artifact )

JarEntry jarEntry = jar.getJarEntry( "org/jenkinsci/plugins/ssegateway/sse/EventSource.js" )

assert jarEntry != null
