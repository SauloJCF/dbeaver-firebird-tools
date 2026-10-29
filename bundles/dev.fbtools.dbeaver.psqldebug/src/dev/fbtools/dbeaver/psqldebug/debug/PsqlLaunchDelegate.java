package dev.fbtools.dbeaver.psqldebug.debug;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.model.ILaunchConfigurationDelegate;

/**
 * A configuração de launch só dá nome à Sessão de debug na view Debug; o launch de verdade sai do diálogo da Rotina
 * ({@code dev.fbtools.dbeaver.psqldebug.launch}). Um "Relaunch" pela view Debug chega aqui e é recusado. Implementa só
 * {@link ILaunchConfigurationDelegate} (sem o {@code ...Delegate2}) para o debug.ui não disparar build do workspace.
 */
public final class PsqlLaunchDelegate implements ILaunchConfigurationDelegate {

    @Override
    public void launch(ILaunchConfiguration configuration, String mode, ILaunch launch, IProgressMonitor monitor)
            throws CoreException {
        throw new CoreException(Status.error(
                "Para depurar de novo, abra a Rotina e use \"Depurar Rotina\" no editor dela ou no menu \"PSQL Debug\"."));
    }
}
