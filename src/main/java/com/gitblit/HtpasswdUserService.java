/*
 * Copyright 2013 Florian Zschocke
 * Copyright 2013 gitblit.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gitblit;

import java.io.File;
import java.io.FileInputStream;
import java.text.MessageFormat;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.digest.Crypt;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.codec.digest.Md5Crypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gitblit.Constants.AccountType;
import com.gitblit.models.UserModel;
import com.gitblit.utils.ArrayUtils;
import com.gitblit.utils.StringUtils;


/**
 * Implementation of a user service using an Apache htpasswd file for authentication.
 * 
 * This user service implement custom authentication using entries in a file created
 * by the 'htpasswd' program of an Apache web server. All currently possible output
 * options of the 'htpasswd' program are supported:
 * clear text, glibc crypt(), Apache MD5 (apr1), unsalted SHA-1
 * 
 * Configuration options:
 * realm.htpasswd.backingUserService - Specify the backing user service that is used
 *                                     to keep the user data other than the password.
 *                                     The default is '${baseFolder}/users.conf'.
 * realm.htpasswd.userfile - The text file with the htpasswd entries to be used for
 *                           authentication.
 *                           The default is '${baseFolder}/htpasswd'.
 * realm.htpasswd.overrideLocalAuthentication - Specify if local accounts are overwritten
 *                                              when authentication matches for an
 *                                              external account.
 * 
 * @author Florian Zschocke
 *
 */
public class HtpasswdUserService extends GitblitUserService
{

    private static final String KEY_BACKING_US = Keys.realm.htpasswd.backingUserService;
    private static final String DEFAULT_BACKING_US = "${baseFolder}/users.conf";

    private static final String KEY_HTPASSWD_FILE = Keys.realm.htpasswd.userfile;
    private static final String DEFAULT_HTPASSWD_FILE = "${baseFolder}/htpasswd";

    private static final String KEY_OVERRIDE_LOCALAUTH = Keys.realm.htpasswd.overrideLocalAuthentication;
    private static final boolean DEFAULT_OVERRIDE_LOCALAUTH = true;



    private IStoredSettings settings;
    private File htpasswdFile;


    private final Logger logger = LoggerFactory.getLogger(HtpasswdUserService.class);

    private final Map<String, String> users = new ConcurrentHashMap<String, String>();

    private volatile long lastModified;




    public HtpasswdUserService()
    {
        super();
    }



    /**
     * Setup the user service.
     * 
     * The HtpasswdUserService extends the GitblitUserService and is thus
     * backed by the available user services provided by the GitblitUserService.
     * In addition the setup tries to read and parse the htpasswd file to be used
     * for authentication.
     * 
     * @param settings
     * @since 0.7.0
     */
    @Override
    public void setup(IStoredSettings settings)
    {
        this.settings = settings;
        
        // This is done in two steps in order to avoid calling GitBlit.getFileOrFolder(String, String) which will segfault for unit tests.
        String file = settings.getString(KEY_BACKING_US, DEFAULT_BACKING_US);
        File realmFile = GitBlit.getFileOrFolder(file);
        serviceImpl = createUserService(realmFile);
        logger.info("Htpasswd User Service backed by " + serviceImpl.toString());

        file = settings.getString(KEY_HTPASSWD_FILE, DEFAULT_HTPASSWD_FILE);
        this.htpasswdFile = GitBlit.getFileOrFolder(file);

        read();

        logger.debug("Read " + users.size() + " users from realm file: " + this.htpasswdFile);
    }



    /**
     * For now, credentials are defined in the htpasswd file and can not be manipulated
     * from Gitblit.
     *
     * @return false
     * @since 1.0.0
     */
    @Override
    public boolean supportsCredentialChanges()
    {
        return false;
    }



    /**
     * Authenticate a user based on a username and password.
     *
     * If the account is determined to be a local account, authentication
     * will be done against the locally stored password.
     * Otherwise, the configured htpasswd file is read. All current output options
     * of htpasswd are supported: clear text, crypt(), Apache MD5 and unsalted SHA-1.
     *
     * @param username
     * @param password
     * @return a user object or null
     */
    @Override
    public UserModel authenticate(String username, char[] password)
    {
        if (isLocalAccount(username)) {
            // local account, bypass htpasswd authentication
            return super.authenticate(username, password);
        }


        read();
        String storedPwd = users.get(username);
        if (storedPwd != null) {
            boolean authenticated = false;
            String passwd = new String(password);

            // test clear text
            if ( storedPwd.equals(new String(password)) ){
                logger.debug("Clear text password matched for user '" + username + "'");
                authenticated = true;
            }
            // test Apache MD5 variant encrypted password
            else if ( storedPwd.startsWith("$apr1$") ) {
                if ( storedPwd.equals(Md5Crypt.apr1Crypt(passwd, storedPwd)) ) {
                    logger.debug("Apache MD5 encoded password matched for user '" + username + "'");
                    authenticated = true;
                }
            }
            // test unsalted SHA password
            else if ( storedPwd.startsWith("{SHA}") ) {
                String passwd64 = Base64.encodeBase64String(DigestUtils.sha1(passwd));
                if ( storedPwd.substring("{SHA}".length()).equals(passwd64) ) {
                    logger.debug("Unsalted SHA-1 encoded password matched for user '" + username + "'");
                    authenticated = true;
                }
            }
            // test libc crypt() encoded password
            else if ( storedPwd.equals(Crypt.crypt(passwd, storedPwd)) ) {
                logger.debug("Libc crypt encoded password matched for user '" + username + "'");
                authenticated = true;
            }


            if (authenticated) {
                logger.debug("Htpasswd authenticated: " + username);

                UserModel user = getUserModel(username);
                if (user == null) {
                    // create user object for new authenticated user
                    user = new UserModel(username);
                }

                // create a user cookie
                if (StringUtils.isEmpty(user.cookie) && !ArrayUtils.isEmpty(password)) {
                    user.cookie = StringUtils.getSHA1(user.username + new String(password));
                }

                // Set user attributes, hide password from backing user service.
                user.password = Constants.EXTERNAL_ACCOUNT;
                user.accountType = getAccountType();

                // Push the looked up values to backing file
                super.updateUserModel(user);

                return user;
            }
        }

        return null;
    }



    /**
     * Determine if the account is to be treated as a local account.
     * 
     * This influences authentication. A local account will be authenticated
     * by the backing user service while an external account will be handled 
     * by this user service.
     * <br/>
     * The decision also depends on the setting of the key
     * realm.htpasswd.overrideLocalAuthentication.
     * If it is set to true, then passwords will first be checked against the
     * htpasswd store. If an account exists and is marked as local in the backing
     * user service, that setting will be overwritten by the result. This
     * means that an account that looks local to the backing user service will
     * be turned into an external account upon valid login of a user that has
     * an entry in the htpasswd file.
     * If the key is set to false, then it is determined if the account is local
     * according to the logic of the GitblitUserService.
     */
    protected boolean isLocalAccount(String username)
    {
        if ( settings.getBoolean(KEY_OVERRIDE_LOCALAUTH, DEFAULT_OVERRIDE_LOCALAUTH) ) {
            read();
            if ( users.containsKey(username) ) return false;
        }
        return super.isLocalAccount(username);
    }



    /**
     * Get the account type used for this user service.
     *
     * We use the generic EXTERNAL type here.
     * 
     * @return AccountType.EXTERNAL
     */
    protected AccountType getAccountType()
    {
        return AccountType.EXTERNAL;
    }



    /**
     * Reads the realm file and rebuilds the in-memory lookup tables.
     */
    protected synchronized void read()
    {
        if (htpasswdFile.exists() && (htpasswdFile.lastModified() != lastModified)) {
//            forceReload = false;
            lastModified = htpasswdFile.lastModified();
            users.clear();

            Pattern entry = Pattern.compile("^([^:]+):(.+)");

            Scanner scanner = null;
            try {
                scanner = new Scanner(new FileInputStream(htpasswdFile));
                while( scanner.hasNextLine()) {
                    String line = scanner.nextLine().trim();
                    if ( !line.isEmpty() &&  !line.startsWith("#") ) {
                        Matcher m = entry.matcher(line);
                        if ( m.matches() ) {
                            users.put(m.group(1), m.group(2));
                        }
                    }
                }
            } catch (Exception e) {
                logger.error(MessageFormat.format("Failed to read {0}", htpasswdFile), e);
            }
            finally {
                if (scanner != null) scanner.close();
            }
        }
    }




    @Override
    public String toString()
    {
        return getClass().getSimpleName() + "(" + ((htpasswdFile != null) ? htpasswdFile.getAbsolutePath() : "null") + ")";
    }




    /*
     * Method only used for unit tests. Return number of users read from htpasswd file.
     */
    protected int getNumberUsers()
    {
        if ( this.users == null ) return -1;
        return this.users.size();
    }
}
