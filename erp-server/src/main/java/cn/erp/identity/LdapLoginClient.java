package cn.erp.identity;

import java.util.Hashtable;
import java.util.Map;
import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.SearchControls;
import javax.naming.ldap.InitialLdapContext;

import org.springframework.stereotype.Component;

@Component
class LdapLoginClient {
    String authenticate(SsoSettings.Provider provider,String username,String userPassword) {
        var config=provider.config();
        InitialLdapContext service=null;
        try {
            service=bind(config,config.get("bindDn"),provider.secret());
            String dn=search(service,config,username);
            // 搜索只取得唯一 DN，实际密码由目录服务以该 DN 重新进行 LDAPS 绑定校验。
            bind(config,dn,userPassword).close();
            return SsoSettings.ldapName(dn);
        } catch(NamingException failure) {
            throw LoginIdentities.authFailed();
        } finally {
            close(service);
        }
    }

    void test(SsoSettings.Provider provider) {
        var config=provider.config();
        InitialLdapContext service=null;
        try {
            service=bind(config,config.get("bindDn"),provider.secret());
            service.getAttributes(config.get("baseDn"),new String[]{"objectClass"});
        } catch(NamingException failure) {
            throw new cn.erp.shared.ApiException(502,"SSO_PROVIDER_UNAVAILABLE","目录服务连接或绑定测试失败");
        } finally {
            close(service);
        }
    }

    private String search(InitialLdapContext service,Map<String,String> config,String username) throws NamingException {
        var controls=new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setCountLimit(2);
        controls.setTimeLimit(4000);
        controls.setReturningObjFlag(false);
        controls.setReturningAttributes(new String[0]);
        var result=service.search(config.get("baseDn"),"("+config.get("userAttribute")+"={0})",new Object[]{username},controls);
        try {
            if (!result.hasMore()) throw LoginIdentities.authFailed();
            String dn=result.next().getNameInNamespace();
            if (result.hasMore()) throw LoginIdentities.authFailed();
            return dn;
        } finally {
            result.close();
        }
    }

    private InitialLdapContext bind(Map<String,String> config,String dn,String password) throws NamingException {
        if (password==null || password.isBlank()) throw LoginIdentities.authFailed();
        var environment=new Hashtable<String,String>();
        environment.put(Context.INITIAL_CONTEXT_FACTORY,"com.sun.jndi.ldap.LdapCtxFactory");
        environment.put(Context.PROVIDER_URL,config.get("serverUrl"));
        environment.put(Context.SECURITY_AUTHENTICATION,"simple");
        environment.put(Context.SECURITY_PRINCIPAL,dn);
        environment.put(Context.SECURITY_CREDENTIALS,password);
        environment.put(Context.REFERRAL,"throw");
        environment.put("com.sun.jndi.ldap.connect.timeout","4000");
        environment.put("com.sun.jndi.ldap.read.timeout","4000");
        return new InitialLdapContext(environment,null);
    }

    private void close(InitialLdapContext context) {
        if (context==null) return;
        try {context.close();}
        catch(NamingException failure) {
            System.getLogger(getClass().getName()).log(System.Logger.Level.DEBUG,"目录连接释放失败，异常类型 {0}",failure.getClass().getSimpleName());
        }
    }
}
