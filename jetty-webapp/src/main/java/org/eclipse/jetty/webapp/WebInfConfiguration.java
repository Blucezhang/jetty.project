//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.webapp;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.JarResource;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;

public class WebInfConfiguration extends AbstractConfiguration
{
    private static final Logger LOG = Log.getLogger(WebInfConfiguration.class);

    public static final String TEMPDIR_CONFIGURED = "org.eclipse.jetty.tmpdirConfigured";

    @Deprecated
    public static final String CONTAINER_JAR_PATTERN = MetaInfConfiguration.CONTAINER_JAR_PATTERN;
    @Deprecated
    public static final String WEBINF_JAR_PATTERN = MetaInfConfiguration.WEBINF_JAR_PATTERN;
    @Deprecated
    public static final String RESOURCE_DIRS = MetaInfConfiguration.RESOURCE_DIRS;

    protected Resource _preUnpackBaseResource;

    public WebInfConfiguration()
    {
    }

    @Override
    public void preConfigure(final WebAppContext context) throws Exception
    {
        //Make a temp directory for the webapp if one is not already set
        resolveTempDirectory(context);

        //Extract webapp if necessary
        unpack (context);
    }

    @Override
    public void configure(WebAppContext context) throws Exception
    {
        Resource web_inf = context.getWebInf();

        // Add WEB-INF classes and lib classpaths
        if (web_inf != null && web_inf.isDirectory() && context.getClassLoader() instanceof WebAppClassLoader)
        {
            // Look for classes directory
            Resource classes= web_inf.addPath("classes/");
            if (classes.exists())
                ((WebAppClassLoader)context.getClassLoader()).addClassPath(classes);

            // Look for jars
            Resource lib= web_inf.addPath("lib/");
            if (lib.exists() || lib.isDirectory())
                ((WebAppClassLoader)context.getClassLoader()).addJars(lib);
        }
    }

    @Override
    public void deconfigure(WebAppContext context) throws Exception
    {
        //if we're not persisting the temp dir contents delete it
        if (!context.isPersistTempDirectory())
        {
            IO.delete(context.getTempDirectory());
        }

        //if it wasn't explicitly configured by the user, then unset it
        Boolean tmpdirConfigured = (Boolean)context.getAttribute(TEMPDIR_CONFIGURED);
        if (tmpdirConfigured != null && !tmpdirConfigured) 
            context.setTempDirectory(null);

        //reset the base resource back to what it was before we did any unpacking of resources
        if (context.getBaseResource() != null)
            context.getBaseResource().close();
        context.setBaseResource(_preUnpackBaseResource);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.webapp.AbstractConfiguration#cloneConfigure(org.eclipse.jetty.webapp.WebAppContext, org.eclipse.jetty.webapp.WebAppContext)
     */
    @Override
    public void cloneConfigure(WebAppContext template, WebAppContext context) throws Exception
    {
        File tmpDir=File.createTempFile(WebInfConfiguration.getCanonicalNameForWebAppTmpDir(context),"",template.getTempDirectory().getParentFile());
        if (tmpDir.exists())
        {
            IO.delete(tmpDir);
        }
        tmpDir.mkdir();
        tmpDir.deleteOnExit();
        context.setTempDirectory(tmpDir);
    }


    /* ------------------------------------------------------------ */
    /**
     * Get a temporary directory in which to unpack the war etc etc.
     * The algorithm for determining this is to check these alternatives
     * in the order shown:
     * <p>
     * A. Try to use an explicit directory specifically for this webapp:
     * <ol>
     * <li>
     * Iff an explicit directory is set for this webapp, use it. Set delete on
     * exit depends on value of persistTempDirectory.
     * </li>
     * <li>
     * Iff javax.servlet.context.tempdir context attribute is set for
     * this webapp &amp;&amp; exists &amp;&amp; writeable, then use it. Set delete on exit depends on
     * value of persistTempDirectory.
     * </li>
     * </ol>
     *
     * <p>
     * B. Create a directory based on global settings. The new directory
     * will be called <code>"Jetty-"+host+"-"+port+"__"+context+"-"+virtualhost+"-"+randomdigits+".dir"</code>
     * <p>
     * If the user has specified the context attribute org.eclipse.jetty.webapp.basetempdir, the
     * directory specified by this attribute will be the parent of the temp dir created. Otherwise,
     * the parent dir is <code>${java.io.tmpdir}</code>. Set delete on exit depends on value of persistTempDirectory.
     *  
     * @param context the context to resolve the temp directory from
     * @throws Exception if unable to resolve the temp directory
     */
    public void resolveTempDirectory (WebAppContext context)
        throws Exception
    {
        //If a tmp directory is already set we should use it
        File tmpDir = context.getTempDirectory();
        if (tmpDir != null)
        {
            configureTempDirectory(tmpDir, context);
            context.setAttribute(TEMPDIR_CONFIGURED, Boolean.TRUE); //the tmp dir was set explicitly
            return;
        }

        // No temp directory configured, try to establish one via the javax.servlet.context.tempdir.
        File servletTmpDir = asFile(context.getAttribute(WebAppContext.TEMPDIR));
        if (servletTmpDir != null)
        {
            // Use as tmpDir
            tmpDir = servletTmpDir;
            configureTempDirectory(tmpDir, context);
            // Ensure Attribute has File object
            context.setAttribute(WebAppContext.TEMPDIR,tmpDir);
            // Set as TempDir in context.
            context.setTempDirectory(tmpDir);
            return;
        }

        //We need to make a temp dir. Check if the user has set a directory to use instead
        //of java.io.tmpdir as the parent of the dir
        File baseTemp = asFile(context.getAttribute(WebAppContext.BASETEMPDIR));
        if (baseTemp != null && baseTemp.isDirectory() && baseTemp.canWrite())
        {
            //Make a temp directory as a child of the given base dir
            makeTempDirectory(baseTemp,context);
            return;
        }

        //Look for a directory named "work" in ${jetty.base} and
        //treat it as parent of a new temp dir (which we will persist)
        File jettyBase = asFile(System.getProperty("jetty.base"));
        if (jettyBase != null)
        {
            File work = new File (jettyBase, "work");
            if (work.exists() && work.isDirectory() && work.canWrite())
            {
                context.setPersistTempDirectory(true);
                makeTempDirectory(work,context);
                return;
            }
        }

        //Make a temp directory in java.io.tmpdir
        makeTempDirectory(new File(System.getProperty("java.io.tmpdir")),context);
    }

    /**
     * Given an Object, return File reference for object.
     * Typically used to convert anonymous Object from getAttribute() calls to a File object.
     * @param fileattr the file attribute to analyze and return from (supports type File and type String, all others return null
     * @return the File object, null if null, or null if not a File or String
     */
    private File asFile(Object fileattr)
    {
        if (fileattr == null)
        {
            return null;
        }
        if (fileattr instanceof File)
        {
            return (File)fileattr;
        }
        if (fileattr instanceof String)
        {
            return new File((String)fileattr);
        }
        return null;
    }



    public void makeTempDirectory (File parent, WebAppContext context)
        throws Exception
    {
        if (parent == null || !parent.exists() || !parent.canWrite() || !parent.isDirectory())
            throw new IllegalStateException("Parent for temp dir not configured correctly: "+(parent==null?"null":"writeable="+parent.canWrite()));

        //Create a name for the webapp     
        String temp = getCanonicalNameForWebAppTmpDir(context);
        File tmpDir = null;
        if (context.isPersistTempDirectory())
        {
            //if it is to be persisted, make sure it will be the same name
            //by not using File.createTempFile, which appends random digits
            tmpDir = new File (parent, temp);
        }
        else
        {
            //ensure file will always be unique by appending random digits
            tmpDir = File.createTempFile(temp, ".dir", parent);
            //delete the file that was created
            tmpDir.delete();
            //and make a directory of the same name
            tmpDir.mkdirs();
        }
        configureTempDirectory(tmpDir, context);

        if(LOG.isDebugEnabled())
            LOG.debug("Set temp dir "+tmpDir);
        context.setTempDirectory(tmpDir);
    }

    public void configureTempDirectory (File dir, WebAppContext context)
    {
        if (dir == null)
            throw new IllegalArgumentException("Null temp dir");

        //if dir exists and we don't want it persisted, delete it
        if (dir.exists() && !context.isPersistTempDirectory())
        {
            if (!IO.delete(dir))
                throw new IllegalStateException("Failed to delete temp dir "+dir);
        }

        //if it doesn't exist make it
        if (!dir.exists())
            dir.mkdirs();

        if (!context.isPersistTempDirectory())
            dir.deleteOnExit();

        //is it useable
        if (!dir.canWrite() || !dir.isDirectory())   
            throw new IllegalStateException("Temp dir "+dir+" not useable: writeable="+dir.canWrite()+", dir="+dir.isDirectory());
    }


    public void unpack (WebAppContext context) throws IOException
    {
        Resource web_app = context.getBaseResource();
        _preUnpackBaseResource = context.getBaseResource();

        if (web_app == null)
        {
            String war = context.getWar();
            if (war!=null && war.length()>0)
                web_app = context.newResource(war);
            else
                web_app=context.getBaseResource();

            if (web_app == null)
                throw new IllegalStateException("No resourceBase or war set for context");

            // Accept aliases for WAR files
            if (web_app.isAlias())
            {
                LOG.debug(web_app + " anti-aliased to " + web_app.getAlias());
                web_app = context.newResource(web_app.getAlias());
            }

            if (LOG.isDebugEnabled())
                LOG.debug("Try webapp=" + web_app + ", exists=" + web_app.exists() + ", directory=" + web_app.isDirectory()+" file="+(web_app.getFile()));
            // Is the WAR usable directly?
            if (web_app.exists() && !web_app.isDirectory() && !web_app.toString().startsWith("jar:"))
            {
                // No - then lets see if it can be turned into a jar URL.
                Resource jarWebApp = JarResource.newJarResource(web_app);
                if (jarWebApp.exists() && jarWebApp.isDirectory())
                    web_app= jarWebApp;
            }

            // If we should extract or the URL is still not usable
            if (web_app.exists()  && (
                (context.isCopyWebDir() && web_app.getFile() != null && web_app.getFile().isDirectory()) ||
                (context.isExtractWAR() && web_app.getFile() != null && !web_app.getFile().isDirectory()) ||
                (context.isExtractWAR() && web_app.getFile() == null) ||
                !web_app.isDirectory())
                )
            {
                // Look for sibling directory.
                File extractedWebAppDir = null;

                if (war!=null)
                {
                    // look for a sibling like "foo/" to a "foo.war"
                    File warfile=Resource.newResource(war).getFile();
                    if (warfile!=null && warfile.getName().toLowerCase(Locale.ENGLISH).endsWith(".war"))
                    {
                        File sibling = new File(warfile.getParent(),warfile.getName().substring(0,warfile.getName().length()-4));
                        if (sibling.exists() && sibling.isDirectory() && sibling.canWrite())
                            extractedWebAppDir=sibling;
                    }
                }

                if (extractedWebAppDir==null)
                    // Then extract it if necessary to the temporary location
                    extractedWebAppDir= new File(context.getTempDirectory(), "webapp");

                if (web_app.getFile()!=null && web_app.getFile().isDirectory())
                {
                    // Copy directory
                    LOG.debug("Copy " + web_app + " to " + extractedWebAppDir);
                    web_app.copyTo(extractedWebAppDir);
                }
                else
                {
                    //Use a sentinel file that will exist only whilst the extraction is taking place.
                    //This will help us detect interrupted extractions.
                    File extractionLock = new File (context.getTempDirectory(), ".extract_lock");

                    if (!extractedWebAppDir.exists())
                    {
                        //it hasn't been extracted before so extract it
                        extractionLock.createNewFile();
                        extractedWebAppDir.mkdir();
                        LOG.debug("Extract " + web_app + " to " + extractedWebAppDir);
                        Resource jar_web_app = JarResource.newJarResource(web_app);
                        jar_web_app.copyTo(extractedWebAppDir);
                        extractionLock.delete();
                    }
                    else
                    {
                        //only extract if the war file is newer, or a .extract_lock file is left behind meaning a possible partial extraction
                        if (web_app.lastModified() > extractedWebAppDir.lastModified() || extractionLock.exists())
                        {
                            extractionLock.createNewFile();
                            IO.delete(extractedWebAppDir);
                            extractedWebAppDir.mkdir();
                            LOG.debug("Extract " + web_app + " to " + extractedWebAppDir);
                            Resource jar_web_app = JarResource.newJarResource(web_app);
                            jar_web_app.copyTo(extractedWebAppDir);
                            extractionLock.delete();
                        }
                    }
                }
                web_app = Resource.newResource(extractedWebAppDir.getCanonicalPath());
            }

            // Now do we have something usable?
            if (!web_app.exists() || !web_app.isDirectory())
            {
                LOG.warn("Web application not found " + war);
                throw new java.io.FileNotFoundException(war);
            }

            context.setBaseResource(web_app);

            if (LOG.isDebugEnabled())
                LOG.debug("webapp=" + web_app);
        }


        // Do we need to extract WEB-INF/lib?
        if (context.isCopyWebInf() && !context.isCopyWebDir())
        {
            Resource web_inf= web_app.addPath("WEB-INF/");

            File extractedWebInfDir= new File(context.getTempDirectory(), "webinf");
            if (extractedWebInfDir.exists())
                IO.delete(extractedWebInfDir);
            extractedWebInfDir.mkdir();
            Resource web_inf_lib = web_inf.addPath("lib/");
            File webInfDir=new File(extractedWebInfDir,"WEB-INF");
            webInfDir.mkdir();

            if (web_inf_lib.exists())
            {
                File webInfLibDir = new File(webInfDir, "lib");
                if (webInfLibDir.exists())
                    IO.delete(webInfLibDir);
                webInfLibDir.mkdir();

                LOG.debug("Copying WEB-INF/lib " + web_inf_lib + " to " + webInfLibDir);
                web_inf_lib.copyTo(webInfLibDir);
            }

            Resource web_inf_classes = web_inf.addPath("classes/");
            if (web_inf_classes.exists())
            {
                File webInfClassesDir = new File(webInfDir, "classes");
                if (webInfClassesDir.exists())
                    IO.delete(webInfClassesDir);
                webInfClassesDir.mkdir();
                LOG.debug("Copying WEB-INF/classes from "+web_inf_classes+" to "+webInfClassesDir.getAbsolutePath());
                web_inf_classes.copyTo(webInfClassesDir);
            }

            web_inf=Resource.newResource(extractedWebInfDir.getCanonicalPath());

            ResourceCollection rc = new ResourceCollection(web_inf,web_app);

            if (LOG.isDebugEnabled())
                LOG.debug("context.resourcebase = "+rc);

            context.setBaseResource(rc);
        }
    }

    /**
     * Create a canonical name for a webapp temp directory.
     * <p>
     * The form of the name is:
     * 
     * <pre>"jetty-"+host+"-"+port+"-"+resourceBase+"-_"+context+"-"+virtualhost+"-"+randomdigits+".dir"</pre>
     *
     *  host and port uniquely identify the server
     *  context and virtual host uniquely identify the webapp
     *  randomdigits ensure every tmp directory is unique
     *  
     * @param context the context to get the canonical name from 
     * @return the canonical name for the webapp temp directory
     */
    public static String getCanonicalNameForWebAppTmpDir (WebAppContext context)
    {
        StringBuffer canonicalName = new StringBuffer();
        canonicalName.append("jetty-");

        //get the host and the port from the first connector
        Server server=context.getServer();
        if (server!=null)
        {
            Connector[] connectors = context.getServer().getConnectors();

            if (connectors.length>0)
            {
                //Get the host
                String host=null;
                int port=0;
                if (connectors!=null && (connectors[0] instanceof NetworkConnector))
                {
                    NetworkConnector connector = (NetworkConnector)connectors[0];
                    host=connector.getHost();
                    port=connector.getLocalPort();
                    if (port < 0)
                        port = connector.getPort();
                }
                if (host == null)
                    host = "0.0.0.0";
                canonicalName.append(host);

                //Get the port
                canonicalName.append("-");

                //if not available (eg no connectors or connector not started),
                //try getting one that was configured.
                canonicalName.append(port);
                canonicalName.append("-");
            }
        }

        //Resource  base
        try
        {
            Resource resource = context.getBaseResource();
            if (resource == null)
            {
                if (context.getWar()==null || context.getWar().length()==0)
                    throw new IllegalStateException("No resourceBase or war set for context");

                // Set dir or WAR
                resource = context.newResource(context.getWar());
            }

            if (resource.getURI().getPath()!=null)
            {
                String tmp = URIUtil.decodePath(resource.getURI().getPath());
                if (tmp.endsWith("/"))
                    tmp = tmp.substring(0, tmp.length()-1);
                if (tmp.endsWith("!"))
                    tmp = tmp.substring(0, tmp.length() -1);
                //get just the last part which is the filename
                int i = tmp.lastIndexOf("/");
                canonicalName.append(tmp.substring(i+1, tmp.length()));
            }
            canonicalName.append("-");
        }
        catch (Exception e)
        {
            LOG.warn("Can't generate resourceBase as part of webapp tmp dir name "+e);
            LOG.debug(e);
        }

        //Context name
        String contextPath = context.getContextPath();
        contextPath=contextPath.replace('/','_');
        contextPath=contextPath.replace('\\','_');
        canonicalName.append(contextPath);

        //Virtual host (if there is one)
        canonicalName.append("-");
        String[] vhosts = context.getVirtualHosts();
        if (vhosts == null || vhosts.length <= 0)
            canonicalName.append("any");
        else
            canonicalName.append(vhosts[0]);

        // sanitize
        for (int i=0;i<canonicalName.length();i++)
        {
            char c=canonicalName.charAt(i);
            if (!Character.isJavaIdentifierPart(c) && "-.".indexOf(c)<0)
                canonicalName.setCharAt(i,'.');
        }

        canonicalName.append("-");
        
        return canonicalName.toString();
    }
}
