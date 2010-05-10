/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2008-2010 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.enterprise.v3.admin;

import com.sun.enterprise.config.serverbeans.Config;
import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.module.common_impl.LogHelper;

import java.io.*;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.api.ActionReport;
import org.glassfish.api.Async;
import org.glassfish.api.I18n;
import org.glassfish.api.Param;
import org.glassfish.api.admin.*;
import org.glassfish.common.util.admin.CommandModelImpl;
import org.glassfish.common.util.admin.MapInjectionResolver;
import org.glassfish.common.util.admin.UnacceptableValueException;
import org.glassfish.config.support.GenericCrudCommand;
import org.glassfish.internal.api.*;

import org.jvnet.hk2.annotations.Inject;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.component.ComponentException;
import org.jvnet.hk2.component.Habitat;
import org.jvnet.hk2.component.InjectionManager;
import org.jvnet.hk2.component.UnsatisfiedDependencyException;
import com.sun.hk2.component.InjectionResolver;

import com.sun.enterprise.universal.GFBase64Decoder;
import com.sun.enterprise.universal.collections.ManifestUtils;
import com.sun.enterprise.universal.glassfish.AdminCommandResponse;
import com.sun.enterprise.util.LocalStringManagerImpl;
import com.sun.enterprise.v3.common.XMLContentActionReporter;
import com.sun.logging.LogDomains;

/**
 * Encapsulates the logic needed to execute a server-side command (for example,
 * a descendant of AdminCommand) including injection of argument values into the
 * command.
 *
 * @author dochez
 * @author tjquinn
 * @author Bill Shannon
 */
@Service
public class CommandRunnerImpl implements CommandRunner {

    private final Logger logger = LogDomains.getLogger(CommandRunnerImpl.class,
                                        LogDomains.ADMIN_LOGGER);
    private final InjectionManager injectionMgr = new InjectionManager();

    @Inject
    private Habitat habitat;

    @Inject
    private ServerContext sc;

    @Inject
    private ServerEnvironment serverEnv;

    @Inject
    private Domain domain;

    private static final String ASADMIN_CMD_PREFIX = "AS_ADMIN_";

    private static final LocalStringManagerImpl adminStrings =
                        new LocalStringManagerImpl(CommandRunnerImpl.class);

    /**
     * Returns an initialized ActionReport instance for the passed type or
     * null if it cannot be found.
     *
     * @param name actiopn report type name
     * @return uninitialized action report or null
     */
    public ActionReport getActionReport(String name) {
        return habitat.getComponent(ActionReport.class, name);
    }

    /**
     * Retuns the command model for a command name.
     *
     * @param commandName command name
     * @param logger logger to log any error messages
     * @return model for this command (list of parameters,etc...),
     *          or null if command is not found
     */
    public CommandModel getModel(String commandName, Logger logger) {
        AdminCommand command = null;
        try {
            command = habitat.getComponent(AdminCommand.class, commandName);
        } catch (ComponentException e) {
            logger.log(Level.SEVERE, "Cannot instantiate " + commandName, e);
            return null;
        }
        return getModel(command);
    }

    /**
     * Obtain and return the command implementation defined by
     * the passed commandName.
     *
     * @param commandName command name as typed by users
     * @param report report used to communicate command status back to the user
     * @param logger logger to log
     * @return command registered under commandName or null if not found
     */
    public AdminCommand getCommand(String commandName, ActionReport report,
                                        Logger logger) {

        AdminCommand command = null;
        try {
            command = habitat.getComponent(AdminCommand.class, commandName);
        } catch (ComponentException e) {
            e.printStackTrace();
            report.setFailureCause(e);
        }
        if (command == null) {
            String msg;

            if (!ok(commandName))
                msg = adminStrings.getLocalString("adapter.command.nocommand",
                                                "No command was specified.");
            else {
                // this means either a non-existent command or
                // an ill-formed command
                if (habitat.getInhabitant(AdminCommand.class, commandName) ==
                        null)  // somehow it's in habitat
                    msg = adminStrings.getLocalString("adapter.command.notfound",                                         "Command {0} not found", commandName);
                else
                    msg = adminStrings.getLocalString("adapter.command.notcreated",
                            "Implementation for the command {0} exists in " +
                            "the system, but it has some errors, " +
                            "check server.log for details", commandName);
            }
            report.setMessage(msg);
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            LogHelper.getDefaultLogger().info(msg);
        }
        return command;
    }

    /**
     * Obtain a new command invocation object.
     * Command invocations can be configured and used
     * to trigger a command execution.
     *
     * @param name name of the requested command to invoke
     * @param report where to place the status of the command execution
     * @return a new command invocation for that command name
     */
    public CommandInvocation getCommandInvocation(String name,
                                                    ActionReport report) {
        return new ExecutionContext(name, report);
    }

    /**
     * Executes the provided command object.
     *
     * @param model model of the command (used for logging and reporting)
     * @param command the command service to execute
     * @param injector injector capable of populating the command parameters
     * @param report will hold the result of the command's execution
     * @param inboundPayload files uploaded from the client
     * @param outboundPayload files downloaded to the client
     */
    private ActionReport doCommand(
            final CommandModel model,
            final AdminCommand command,
            final InjectionResolver<Param> injector,
            final ActionReport report,
            final Payload.Inbound inboundPayload,
            final Payload.Outbound outboundPayload) {

        report.setActionDescription(model.getCommandName() + " AdminCommand");

        final AdminCommandContext context = new AdminCommandContext(
                LogDomains.getLogger(command.getClass(),
                    LogDomains.ADMIN_LOGGER),
                report, inboundPayload, outboundPayload);

        try {
            GenericCrudCommand c = GenericCrudCommand.class.cast(command);
            c.setInjectionResolver(injector);
        } catch(ClassCastException e) {
            // do nothing.
        }

        LocalStringManagerImpl localStrings =
            new LocalStringManagerImpl(command.getClass());

        // Let's get the command i18n key
        I18n i18n = model.getI18n();
        String i18n_key = "";
        if (i18n!=null) {
            i18n_key = i18n.value();
        }

        // inject
        try {
            injectionMgr.inject(command, injector);
        } catch (UnsatisfiedDependencyException e) {
            Param param = e.getAnnotation(Param.class);
            CommandModel.ParamModel paramModel=null;
            for (CommandModel.ParamModel pModel : model.getParameters()) {
                if (pModel.getParam().equals(param)) {
                    paramModel = pModel;
                    break;
                }
            }
            String errorMsg;
            final String usage = getUsageText(command, model);
            if (paramModel != null) {
                String paramName = paramModel.getName();
                String paramDesc =
                    getParamDescription(localStrings, i18n_key, paramModel);

                if (param.primary()) {
                    errorMsg = adminStrings.getLocalString("commandrunner.operand.required",
                                                           "Operand required.");
                } else if (param.password()) {
                    errorMsg = adminStrings.getLocalString("adapter.param.missing.passwordfile",
                                "{0} command requires the passwordfile " +
                                    "parameter containing {1} entry.",
                                model.getCommandName(), paramName);
                } else if (paramDesc != null) {
                    errorMsg = adminStrings.getLocalString("admin.param.missing",
                                "{0} command requires the {1} parameter ({2})",
                                model.getCommandName(), paramName, paramDesc);

                } else {
                    errorMsg = adminStrings.getLocalString("admin.param.missing.nodesc",
                                "{0} command requires the {1} parameter",
                                model.getCommandName(), paramName);
                }
            } else {
                errorMsg = adminStrings.getLocalString("admin.param.missing.nofound",
                           "Cannot find {1} in {0} command model, file a bug",
                           model.getCommandName(), e.getUnsatisfiedName());
            }
            logger.severe(errorMsg);
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setMessage(errorMsg);
            report.setFailureCause(e);
            ActionReport.MessagePart childPart =
                report.getTopMessagePart().addChild();
            childPart.setMessage(usage);
            return report;
        } catch (ComponentException e) {
            // If the cause is UnacceptableValueException -- we want the message
            // from it.  It is wrapped with a less useful Exception.

            Exception exception = e;
            Throwable cause = e.getCause();
            if (cause != null &&
                    (cause instanceof UnacceptableValueException ||
                        cause instanceof IllegalArgumentException)) {
                // throw away the wrapper.
                exception = (Exception)cause;
            }
            logger.log(Level.SEVERE, "invocation.exception", exception);
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setMessage(exception.getMessage());
            report.setFailureCause(exception);
            ActionReport.MessagePart childPart =
                report.getTopMessagePart().addChild();
            childPart.setMessage(getUsageText(command, model));
            return report;
        }

        // We need to set context CL to common CL before executing
        // the command. See issue #5596
        final AdminCommand wrappedComamnd = new AdminCommand() {
            public void execute(AdminCommandContext context) {
                Thread thread = Thread.currentThread();
                ClassLoader origCL = thread.getContextClassLoader();
                ClassLoader ccl = sc.getCommonClassLoader();
                if (origCL != ccl) {
                    try {
                        thread.setContextClassLoader(ccl);
                        command.execute(context);
                    } finally {
                        thread.setContextClassLoader(origCL);
                    }
                } else {
                    command.execute(context);
                }
            }
        };

        // the command may be an asynchronous command, so we need to check
        // for the @Async annotation.
        Async async = command.getClass().getAnnotation(Async.class);
        if (async == null) {
            try {
                wrappedComamnd.execute(context);
            } catch(Throwable e) {
                logger.log(Level.SEVERE,
                        adminStrings.getLocalString("adapter.exception",
                                "Exception in command execution : ", e), e);
                report.setMessage(e.toString());
                report.setActionExitCode(ActionReport.ExitCode.FAILURE);
                report.setFailureCause(e);
            }
        } else {
            Thread t = new Thread() {
                public void run() {
                    try {
                        wrappedComamnd.execute(context);
                    } catch (RuntimeException e) {
                        logger.log(Level.SEVERE, e.getMessage(), e);
                    }
                }
            };
            t.setPriority(async.priority());
            t.start();
            report.setActionExitCode(ActionReport.ExitCode.SUCCESS);
            report.setMessage(
                    adminStrings.getLocalString("adapter.command.launch",
                    "Command {0} was successfully initiated asynchronously.",
                            model.getCommandName()));
        }
        return context.getActionReport();
    }

    private static String getParamDescription(
            LocalStringManagerImpl localStrings,
            String i18nKey,
            CommandModel.ParamModel model) {

        I18n i18n = model.getI18n();
        String paramDesc;
        if (i18n == null) {
            paramDesc =
                localStrings.getLocalString(i18nKey+"."+model.getName(), "");
        } else {
            paramDesc = localStrings.getLocalString(i18n.value(), "");
        }
        if (paramDesc == null) {
            paramDesc = "";
//          paramDesc = adminStrings.getLocalString("adapter.nodesc",
//                                                  "no description provided");
        }
        return paramDesc;
    }

    /**
     * Get the usage-text of the command.
     * Check if <command-name>.usagetext is defined in LocalString.properties.
     * If defined, then use the usagetext from LocalString.properties else
     * generate the usagetext from Param annotations in the command class.
     *
     * @param command class
     * @param model command model
     * @return usagetext
     */
    static String getUsageText(AdminCommand command, CommandModel model) {
        StringBuffer usageText = new StringBuffer();
        I18n i18n = model.getI18n();
        String i18nKey = null;

        final LocalStringManagerImpl lsm =
            new LocalStringManagerImpl(command.getClass());
        if (i18n!=null) {
            i18nKey = i18n.value();
        }
	String usage;
        if (i18nKey != null &&
		ok(usage = lsm.getLocalString(i18nKey+".usagetext", ""))) {
	    usageText.append(
                adminStrings.getLocalString("adapter.usage", "Usage: "));
            usageText.append(usage);
	    return usageText.toString();
        } else {
            return generateUsageText(model);
        }
    }

    /**
     * Generate the usage-text from the annotated Param in the command class.
     *
     * @param model command model
     * @return generated usagetext
     */
    private static String generateUsageText(CommandModel model) {
        StringBuffer usageText = new StringBuffer();
	usageText.append(
            adminStrings.getLocalString("adapter.usage", "Usage: "));
        usageText.append(model.getCommandName());
        usageText.append(" ");
        StringBuffer operand = new StringBuffer();
        for (CommandModel.ParamModel pModel : model.getParameters()) {
            final Param param = pModel.getParam();
            final String paramName = pModel.getName();
            // do not want to display password as an option
            if (param.password())
                continue;
            final boolean optional = param.optional();
            final Class<?> ftype = pModel.getType();
            Object fvalue = null;
            String fvalueString = null;
            try {
                fvalue = param.defaultValue();
                if (fvalue != null)
                    fvalueString = fvalue.toString();
            } catch (Exception e) {
                // just leave it as null...
            }
            // this is a param.
            if (param.primary()) {
                if (optional) {
                    operand.append("[").append(paramName).append("] ");
                } else {
                    operand.append(paramName).append(" ");
                }
                continue;
            }

            if (optional)
                usageText.append("[");

            usageText.append("--").append(paramName);
            if (ok(param.defaultValue())) {
                usageText.append("=").append(param.defaultValue());
            } else if (ftype.isAssignableFrom(String.class)) {
                // check if there is a default value assigned
                if (ok(fvalueString)) {
                    usageText.append("=").append(fvalueString);
                } else {
                    usageText.append("=").append(paramName);
                }
            } else if (ftype.isAssignableFrom(Boolean.class)) {
                // note: There is no defaultValue for this param.  It might
                // hava  value -- but we don't care -- it isn't an official
                // default value.
                usageText.append("=").append("true|false");
            } else {
                usageText.append("=").append(paramName);
            }

            if (optional)
                usageText.append("] ");
            else
                usageText.append(" ");
        }
        usageText.append(operand);
        return usageText.toString();
    }

    public void getHelp(AdminCommand command, ActionReport report) {

        CommandModel model = getModel(command);
        report.setActionDescription(model.getCommandName() + " help");
        LocalStringManagerImpl localStrings =
                                new LocalStringManagerImpl(command.getClass());
        // Let's get the command i18n key
        I18n i18n = command.getClass().getAnnotation(I18n.class);
        String i18nKey = "";

        if (i18n != null) {
            i18nKey = i18n.value();
        }
	// XXX - this is a hack for now.  if the request mapped to an
	// XMLContentActionReporter, that means we want the command metadata.
	if (report instanceof XMLContentActionReporter) {
	    getMetadata(command, model, report);
	} else {
	    report.setMessage(model.getCommandName() + " - " +
                                    localStrings.getLocalString(i18nKey, ""));
	    report.getTopMessagePart().addProperty("SYNOPSIS",
                                                getUsageText(command, model));
	    for (CommandModel.ParamModel param : model.getParameters()) {
		addParamUsage(report, localStrings, i18nKey, param);
	    }
	    report.setActionExitCode(ActionReport.ExitCode.SUCCESS);
	}
    }

    /**
     * Return the metadata for the command.  We translate the parameter
     * and operand information to parts and properties of the ActionReport,
     * which will be translated to XML elements and attributes by the
     * XMLContentActionReporter.
     *
     * @param command the command
     * @param model the CommandModel describing the command
     * @param report	the (assumed to be) XMLContentActionReporter
     */
    private void getMetadata(AdminCommand command, CommandModel model,
	    ActionReport report) {
        LocalStringManagerImpl localStrings =
		new LocalStringManagerImpl(command.getClass());

        // Let's get the command i18n key
        I18n i18n = model.getI18n();
        String i18n_key = "";
        if (i18n!=null) {
            i18n_key = i18n.value();
        }

	ActionReport.MessagePart top = report.getTopMessagePart();
	ActionReport.MessagePart cmd = top.addChild();
	// <command name="name">
	cmd.setChildrenType("command");
	cmd.addProperty("name", model.getCommandName());
	if (model.unknownOptionsAreOperands())
	    cmd.addProperty("unknown-options-are-operands", "true");
	String usage = localStrings.getLocalString(i18n_key + ".usagetext", "");
	if (ok(usage))
	    cmd.addProperty("usage", usage);
	CommandModel.ParamModel primary = null;
	// for each parameter add
	// <option name="name" type="type" short="s" default="default"
	//   acceptable-values="list"/>
	for (CommandModel.ParamModel p : model.getParameters()) {
	    Param param = p.getParam();
	    if (param.primary()) {
		primary = p;
		continue;
	    }
	    ActionReport.MessagePart ppart = cmd.addChild();
	    ppart.setChildrenType("option");
	    ppart.addProperty("name", p.getName());
	    ppart.addProperty("type", typeOf(p));
	    ppart.addProperty("optional", Boolean.toString(param.optional()));
	    String paramDesc = getParamDescription(localStrings, i18n_key, p);
	    if (ok(paramDesc))
		ppart.addProperty("description", paramDesc);
	    if (ok(param.shortName()))
		ppart.addProperty("short", param.shortName());
	    if (ok(param.defaultValue()))
		ppart.addProperty("default", param.defaultValue());
	    if (ok(param.acceptableValues()))
		ppart.addProperty("acceptable-values", param.acceptableValues());
	}

	// are operands allowed?
	if (primary != null) {
	    // for the operand(s), add
	    // <operand type="type" min="0/1" max="1"/>
	    ActionReport.MessagePart primpart = cmd.addChild();
	    primpart.setChildrenType("operand");
	    primpart.addProperty("name", primary.getName());
	    primpart.addProperty("type", typeOf(primary));
	    primpart.addProperty("min",
		    primary.getParam().optional() ? "0" : "1");
	    primpart.addProperty("max", primary.getParam().multiple() ?
                        Integer.toString(Integer.MAX_VALUE) : "1");
	    String desc = getParamDescription(localStrings, i18n_key, primary);
	    if (ok(desc))
		primpart.addProperty("description", desc);
	}
    }

    /**
     * Map a Java type to one of the types supported by the asadmin client.
     * Currently supported types are BOOLEAN, FILE, PROPERTIES, PASSWORD, and
     * STRING.  (All of which should be defined constants on some class.)
     *
     * @param p the Java type
     * @return	the string representation of the asadmin type
     */
    private static String typeOf(CommandModel.ParamModel p) {
	Class t = p.getType();
	if (t == Boolean.class)
	    return "BOOLEAN";
	else if (t == File.class)
	    return "FILE";
	else if (t == Properties.class)	// XXX - allow subclass?
	    return "PROPERTIES";
	else if (p.getParam().password())
	    return "PASSWORD";
	else
	    return "STRING";
    }

    public InputStream getManPage(String commandName, AdminCommand command) {
        // bnevins -- too bad there is no AdminCommand baseclass.  We could make it
        // do the work but, alas, there is no such thing.
        Class clazz = command.getClass();
        Package pkg = clazz.getPackage();
        String manPage = pkg.getName().replace('.', '/');
        manPage += "/" + commandName + ".1";
        ClassLoader loader = clazz.getClassLoader();
        InputStream in = loader.getResourceAsStream(manPage);
        return in;
    }

    private void addParamUsage(
            ActionReport report,
            LocalStringManagerImpl localStrings,
            String i18nKey,
            CommandModel.ParamModel model) {
        Param param = model.getParam();
        if (param!=null) {
             // this is a param.
            String paramName = model.getName();
            //do not want to display password in the usage
            if (param.primary())
                return;
            if (param.primary()) {
                //if primary then it's an operand
                report.getTopMessagePart().addProperty(paramName+"_operand",
                            getParamDescription(localStrings, i18nKey, model));
            } else {
                report.getTopMessagePart().addProperty(paramName,
                            getParamDescription(localStrings, i18nKey, model));
            }
        }
    }

    private static boolean ok(String s) {
        return s != null && s.length() > 0;
    }

    /**
     * Validate the paramters with the Param annotation.  If parameter is
     * not defined as a Param annotation then it's an invalid option.
     * If parameter's key is "DEFAULT" then it's a operand.
     *
     * @param model command model
     * @param parameters parameters from URL
     * @throws ComponentException if option is invalid
     */
    static void validateParameters(final CommandModel model,
                    final ParameterMap parameters) throws ComponentException {

        // loop through parameters and make sure they are
        // part of the Param declared field
        for (Map.Entry<String,List<String>> entry : parameters.entrySet()) {
            String key = entry.getKey();

            // to do, we should validate meta-options differently.
            if (key.equals("DEFAULT") || key.startsWith(ASADMIN_CMD_PREFIX)) {
                continue;
            }

            // help and Xhelp are meta-options that are handled specially
            if (key.equals("help") || key.equals("Xhelp")) {
                continue;
            }

            // check if key is a valid Param Field
            boolean validOption = false;
            // loop through the Param field in the command class
            // if either field name or the param name is equal to
            // key then it's a valid option
            for (CommandModel.ParamModel pModel : model.getParameters()) {
                validOption = pModel.isParamId(key);
                if (validOption)
                    break;
                if (pModel.getParam().password()) {
                    validOption = pModel.isParamId(
                        ASADMIN_CMD_PREFIX + key.toUpperCase(Locale.ENGLISH));
                    if (validOption)
                        break;
                }
            }
            if (!validOption) {
                throw new ComponentException(" Invalid option: " + key);
            }
        }
    }

    /**
     * Check if the variable, "skipParamValidation" is defined in the command
     * class.  If defined and set to true, then parameter validation will be
     * skipped from that command.
     * This is used mostly for command referencing.  For example the
     * list-applications command references list-components command and you
     * don't want to define the same params from the class that implements
     * list-components.
     *
     * @param command - AdminCommand class
     * @return true if to skip param validation, else return false.
     */
    static boolean skipValidation(AdminCommand command) {
        try {
            final Field f =
                command.getClass().getDeclaredField("skipParamValidation");
            f.setAccessible(true);
            if (f.getType().isAssignableFrom(boolean.class)) {
                return f.getBoolean(command);
            }
        } catch (NoSuchFieldException e) {
            return false;
        } catch (IllegalAccessException e) {
            return false;
        }
        //all else return false
        return false;
    }

    private static String encodeManPage(InputStream in) {
        try {
            if (in == null)
                return null;

            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            String line;
            StringBuilder sb = new StringBuilder();

            while ((line = br.readLine()) != null) {
                sb.append(line);
                sb.append(ManifestUtils.EOL_TOKEN);
            }
            return sb.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    private static CommandModel getModel(AdminCommand command) {

        if (command instanceof CommandModelProvider) {
            return ((CommandModelProvider) command).getModel();
        } else {
            return new CommandModelImpl(command.getClass());
        }
    }

    /**
     * Called from ExecutionContext.execute.
     */
    private void doCommand(ExecutionContext inv, AdminCommand command) {

        if (command == null) {
            command = getCommand(inv.name(), inv.report(), logger);
            if (command == null) {
                return;
            }
        }

        CommandModel model;
        try {
            GenericCrudCommand c = GenericCrudCommand.class.cast(command);
            model = c.getModel();
        } catch (ClassCastException e) {
            model = new CommandModelImpl(command.getClass());
        }
        if (inv.typedParams() != null) {
            InjectionResolver<Param> injectionTarget =
                new DelegatedInjectionResolver(model, inv.typedParams());
            doCommand(model, command, injectionTarget, inv.report(),
                        inv.inboundPayload(), inv.outboundPayload());
            return;
        }

        ParameterMap parameters = inv.parameters();
        if (parameters == null) {
            // no parameters, pass an empty collection
            parameters = new ParameterMap();            
        }

        final ActionReport report = inv.report();

        if (isSet(parameters, "help") || isSet(parameters, "Xhelp")) {
            InputStream in = getManPage(model.getCommandName(), command);
            String manPage = encodeManPage(in);

            if (manPage != null && isSet(parameters, "help")) {
                inv.report().getTopMessagePart().addProperty("MANPAGE", manPage);
            } else {
                report.getTopMessagePart().addProperty(
                                AdminCommandResponse.GENERATED_HELP, "true");
                getHelp(command, report);
            }
            return;
        }

        try {
            if (!skipValidation(command)) {
                validateParameters(model, parameters);
            }
        } catch (ComponentException e) {
            // If the cause is UnacceptableValueException -- we want the message
            // from it.  It is wrapped with a less useful Exception.

            Exception exception = e;
            Throwable cause = e.getCause();
            if (cause != null &&
                    (cause instanceof UnacceptableValueException)) {
                // throw away the wrapper.
                exception = (Exception)cause;
            }
            logger.severe(exception.getMessage());
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setMessage(exception.getMessage());
            report.setFailureCause(exception);
            ActionReport.MessagePart childPart =
                                report.getTopMessagePart().addChild();
            childPart.setMessage(getUsageText(command, model));
            return;
        }

        // initialize the injector.
        InjectionResolver<Param> injectionMgr =
                    new MapInjectionResolver(model, parameters);
        doCommand(model, command, injectionMgr, report,
                    inv.inboundPayload(), inv.outboundPayload());
        /*
         * Command execution completed; If this is DAS and the command succeeded,
         * time to replicate; At this point we will get the appropriate ClusterExecutor
         * and give it complete control; We will let the executor take care all considerations
         * (like RuntimeType settings, FailurePolicy settings etc)
         * and just give the final execution results which we will set as is in the Final
         * Action report returned to the caller.
         */
        // TODO : Remove this flag once CLIs are compliant with @Cluster requirements
        Config cfg = domain.getConfigs().getConfigByName("server-config");
        List<String> jvmOpts=cfg.getJavaConfig().getJvmOptions();
        if(!jvmOpts.contains("-Dcommand.replication.enabled=true"))
            return;
        //TODO : Remove the above flag check by MS2
        
        if(serverEnv.getRuntimeType().isDas() &&
                report.getActionExitCode().equals(ActionReport.ExitCode.SUCCESS)) {
            Cluster clAnnotation = command.getClass().getAnnotation(Cluster.class);
            ClusterExecutor executor = null;
            if(clAnnotation != null && clAnnotation.executor() != null) {
                try {
                    executor = clAnnotation.executor().newInstance();
                } catch(InstantiationException iex) {
                    logger.severe(adminStrings.getLocalString("commandrunner.clusterexecutor.instantiationfailure",
                            "Unable to initialize specified exector class {0} : {1}", clAnnotation.executor(),
                            iex.getLocalizedMessage()));
                } catch(IllegalAccessException aex) {
                    logger.severe(adminStrings.getLocalString("commandrunner.clusterexecutor.instantiationfailure",
                            "Unable to initialize specified exector class {0} : {1}", clAnnotation.executor(),
                            aex.getLocalizedMessage()));
                }
            } else {
                executor = habitat.getByContract(ClusterExecutor.class);
            }
            if(executor == null) {
                logger.severe(adminStrings.getLocalString("commandrunner.clusterexecutor.notinitialized",
                        "Unable to get an instance of ClusterExecutor; Cannot dynamically reconfigure instances"));
                return;
            }
            report.setActionExitCode(executor.execute(model.getCommandName(), command,
                    new AdminCommandContext(logger, report, inv.inboundPayload(), inv.outboundPayload()),
                    parameters));
        }
    }

    /*
     * Some private classes used in the implementation of CommandRunner.
     */

    /**
     * ExecutionContext is a CommandInvocation, which
     * defines a command excecution context like the requested
     * name of the command to execute, the parameters of the command, etc.
     */
    private class ExecutionContext implements CommandInvocation {

        protected final String name;
        protected final ActionReport report;
        protected ParameterMap params;
        protected CommandParameters paramObject;
        protected Payload.Inbound inbound;
        protected Payload.Outbound outbound;

        private ExecutionContext(String name, ActionReport report) {
            this.name = name;
            this.report = report;
        }

        public CommandInvocation parameters(CommandParameters paramObject) {
            this.paramObject = paramObject;
            return this;
        }

        public CommandInvocation parameters(ParameterMap params) {
            this.params = params;
            return this;
        }

        public CommandInvocation inbound(Payload.Inbound inbound) {
            this.inbound = inbound;
            return this;
        }

        public CommandInvocation outbound(Payload.Outbound outbound) {
            this.outbound = outbound;
            return this;
        }

        public void execute() {
            execute(null);
        }

        private ParameterMap parameters() { return params; }
        private CommandParameters typedParams() { return paramObject; }
        private String name() { return name; }
        private ActionReport report() { return report; }
        private Payload.Inbound inboundPayload() { return inbound; }
        private Payload.Outbound outboundPayload() { return outbound; }

        public void execute(AdminCommand command) {
            CommandRunnerImpl.this.doCommand(this, command);

        }
    }

    /**
     * An InjectionResolver that uses an Object as the source of
     * the data to inject.
     */
    private static class DelegatedInjectionResolver
                            extends InjectionResolver<Param> {
        private final CommandModel model;
        private final CommandParameters parameters;

        public DelegatedInjectionResolver(CommandModel model,
                                            CommandParameters parameters) {
            super(Param.class);
            this.model = model;
            this.parameters = parameters;
        }

        @Override
        public boolean isOptional(AnnotatedElement element, Param annotation) {
            String name = CommandModel.getParamName(annotation, element);
            CommandModel.ParamModel param = model.getModelFor(name);
            return param.getParam().optional();
        }

        @Override
        public <V> V getValue(Object component, AnnotatedElement target,
                                        Class<V> type) throws ComponentException {

            // look for the name in the list of parameters passed.
            if (target instanceof Field) {
                Field targetField = (Field) target;
                try {
                    Field sourceField =
                        parameters.getClass().getField(targetField.getName());
                    targetField.setAccessible(true);
                    Object paramValue = sourceField.get(parameters);
/*
                    if (paramValue==null) {
                        return convertStringToObject(target, type,
                                                        param.defaultValue());
                    }
*/
                    // XXX temp fix, to revisit
                    if (paramValue != null) {
                        checkAgainstAcceptableValues(target,
                                                    paramValue.toString());
                    }
                    return type.cast(paramValue);
                } catch (IllegalAccessException e) {
                } catch (NoSuchFieldException e) {
                }
            }
            return null;
        }

        private static void checkAgainstAcceptableValues(
                                AnnotatedElement target, String paramValueStr) {
            Param param = target.getAnnotation(Param.class);
            String acceptable = param.acceptableValues();
            String paramName = CommandModel.getParamName(param, target);

            if (ok(acceptable) && ok(paramValueStr)) {
                String[] ss = acceptable.split(",");

                for (String s : ss) {
                    if (paramValueStr.equals(s.trim()))
                        return;         // matched, value is good
                }

                // didn't match any, error
                throw new UnacceptableValueException(
                    adminStrings.getLocalString(
                        "adapter.command.unacceptableValue",
                        "Invalid parameter: {0}.  Its value is {1} " +
                            "but it isn''t one of these acceptable values: {2}",
                        paramName,
                        paramValueStr,
                        acceptable));
            }
        }
    }

    /**
     * Is the boolean valued parameter specified?
     * If so, and it has a value, is the value "true"?
     */
    private static boolean isSet(ParameterMap params, String name) {
        String val = params.getOne(name);
        if (val == null)
            return false;
        return val.length() == 0 || Boolean.valueOf(val).booleanValue();
    }
}
