package com.sun.s1asdev.connector.failallconnections.ejb;

import javax.ejb.*;
import java.rmi.*;

public interface SimpleSessionHome extends EJBHome
{
    SimpleSession create()
        throws RemoteException, CreateException;

}
