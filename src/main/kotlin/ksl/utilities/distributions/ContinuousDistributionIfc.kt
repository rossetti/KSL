package ksl.utilities.distributions

import ksl.utilities.random.rvariable.GetRVariableIfc

interface ContinuousDistributionIfc : CDFIfc, PDFIfc, DomainIfc, GetRVariableIfc {
}