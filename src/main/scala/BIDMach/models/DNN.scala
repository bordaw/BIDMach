package BIDMach.models

import BIDMat.{Mat,SBMat,CMat,DMat,FMat,IMat,HMat,GMat,GIMat,GSMat,SMat,SDMat}
import BIDMat.MatFunctions._
import BIDMat.SciFunctions._
import BIDMach.datasources._
import BIDMach.updaters._
import BIDMach.mixins._
import BIDMach._

/**
 * Basic DNN class. Learns a supervised map from input blocks to output (target) data blocks. There are currently 16 layer types:
 - InputLayer: just a placeholder for the first layer which is loaded with input data blocks. No learnable params. 
 - FCLayer: Fully-Connected Linear layer. Has a matrix of learnable params which is the input-output map. 
 - RectLayer: Rectifying one-to-one layer. No params.
 - GLMLayer: a one-to-one layer with GLM mappings (linear, logistic, abs-logistic and SVM). No learnable params. 
 - NormLayer: normalizing layer that adds a derivative term based on the difference between current layer norm and a target norm. 
   No learnable params. The target norm and weight of this derivative term can be specified. 
 - DropoutLayer: A layer that implements random dropout. No learnable params, but dropout fraction can be specified. 
 - AddLayer: adds input layers element-wise.
 - MulLayer: multiplies input layers element-wise. Will also perform edge operations (one input can be a scalar). 
 - Softmax: a softmax (normalized exponential) layer.
 - Tanh: Hyperbolic tangent non-linearity.
 - Sigmoid: Logistic function non-linearity.
 - Softplus: smooth ReLU unit.
 - Cut: needed in each cycle of cyclic networks to allow caching to work. 
 - Ln: natural logarithm
 - Exp: exponential
 - Sum: column-wise sum
 *
 * The network topology is specified by opts.layers which is a sequence of "LayerSpec" objects. There is a LayerSpec
 * Class for each Layer class, which holds the params for defining that layer. Currently only four LayerSpec types need params:
 - FC: "outside" holds the output dimensions of the FClayer (input dimension set by previous layer). 
 - GLM: "links" holds the links matrix (integer specs for loss types, see GLM), for the output of that layer. Its size should match the number of targets.
 - Norm: "targetNorm" holds a target per-element norm, and "weight" is the weight for this term in the derivative calculation.
 - Dropout: "frac" holds the fraction of neurons to retain.
 *
 * Each LayerSpec instance has up to two inputs which are other LayerSpec instances (or null). This graph structure can be cyclic. 
 * When the model is created, the Layer structure mimics the LayerSpec structure. 
 */

class DNN(override val opts:DNN.Opts = new DNN.Options) extends Model(opts) {
  var layers:Array[Layer] = null;
  var targmap:Mat = null;
  var mask:Mat = null;

  override def init() = {
	  mats = datasource.next;
	  var nfeats = mats(0).nrows;
	  datasource.reset;
	  targmap = if (opts.targmap.asInstanceOf[AnyRef] != null) convertMat(opts.targmap) else null;
	  mask = if (opts.dmask.asInstanceOf[AnyRef] != null) convertMat(opts.dmask) else null;
	  layers = new Array[Layer](opts.layers.length);
	  var imodel = 0;
	  if (refresh) {
	  	val nmodelmats = opts.layers.count(_ match {case x:DNN.ModelLayerSpec => true; case _ => false});
	    setmodelmats(new Array[Mat](nmodelmats));
	  }
	  for (i <- 0 until opts.layers.length) {
	  	opts.layers(i) match {

	  	case lspec:DNN.FC => {
	  	  val fclayer = new FCLayer(imodel, lspec.constFeat, lspec.aopts);
	  		layers(i) = fclayer;
	  		if (refresh) modelmats(imodel) = convertMat(normrnd(0, 1, lspec.outsize, nfeats + (if (lspec.constFeat) 1 else 0)));
	  		nfeats = lspec.outsize;
	  		if (lspec.aopts != null) fclayer.initADAGrad
	  		imodel += 1;
	  	}
	  	case lspec:DNN.ReLU => {
	  		layers(i) = new ReLULayer;
	  	}
	  	case lspec:DNN.Input => {
	  		layers(i) = new InputLayer;
	  	}
	  	case lspec:DNN.GLM => {
	  		layers(i) = new GLMLayer(lspec.links);
	  	}
	  	case lspec:DNN.Norm => {
	  		layers(i) = new NormLayer(lspec.targetNorm, lspec.weight);
	  	}
	  	case lspec:DNN.Dropout => {
	  		layers(i) = new DropoutLayer(lspec.frac);
	  	}
	  	case lspec:DNN.Add => {
	  		layers(i) = new AddLayer;
	  	}
	  	case lspec:DNN.Mul => {
	  		layers(i) = new MulLayer;
	  	}
	  	case lspec:DNN.Softmax => {
	  		layers(i) = new SoftmaxLayer;
	  	}
	  	case lspec:DNN.Tanh => {
	  		layers(i) = new TanhLayer;
	  	}
	  	case lspec:DNN.Sigmoid => {
	  		layers(i) = new SigmoidLayer;
	  	}
	  	case lspec:DNN.Softplus => {
	  		layers(i) = new SoftplusLayer;
	  	}
	  	case lspec:DNN.Cut => {
	  		layers(i) = new CutLayer;
	  	}
	  	case lspec:DNN.Ln => {
	  		layers(i) = new LnLayer;
	  	}
	  	case lspec:DNN.Exp => {
	  		layers(i) = new ExpLayer;
	  	}
	  	case lspec:DNN.Sum => {
	  		layers(i) = new SumLayer;
	  	}
	  	case lspec:DNN.Lag => {
	  		layers(i) = new LagLayer(lspec.siz);
	  	}
	  	}
	  	opts.layers(i).myLayer = layers(i);
	  }
	  updatemats = new Array[Mat](modelmats.length);
	  for (i <- 0 until modelmats.length) {
	    modelmats(i) = convertMat(modelmats(i));
	    updatemats(i) = modelmats(i).zeros(modelmats(i).nrows, modelmats(i).ncols);
	  } 		
	  for (i <- 0 until opts.layers.length) {
	  	if (opts.layers(i).input.asInstanceOf[AnyRef] != null) layers(i).input = opts.layers(i).input.myLayer.asInstanceOf[DNN.this.Layer];
	  	if (opts.layers(i).inputs != null) {
	  	  (0 until opts.layers(i).inputs.length).map((j:Int) => layers(i).inputs(j) = opts.layers(i).inputs(j).myLayer.asInstanceOf[DNN.this.Layer]);
	  	} 
	  }
  }
  
  
  def doblock(gmats:Array[Mat], ipass:Int, pos:Long):Unit = {
  	layers(0).data = gmats(0);
    if (targmap.asInstanceOf[AnyRef] != null) {
    	layers(layers.length-1).target = targmap * gmats(0);
    } else {
    	layers(layers.length-1).target = gmats(1);
    }
    if (mask.asInstanceOf[AnyRef] != null) {
    	modelmats(0) ~ modelmats(0) ∘ mask;
    }
    var i = 1
    while (i < layers.length) {
      layers(i).forward;
      i += 1;
    }
    while (i > 1) {
      i -= 1;
      layers(i).backward(ipass, pos);
    }
    if (mask.asInstanceOf[AnyRef] != null) {
    	updatemats(0) ~ updatemats(0) ∘ mask;
    }
  }
  
  def evalblock(mats:Array[Mat], ipass:Int, here:Long):FMat = {  
    layers(0).data = gmats(0);
    val targ = if (targmap.asInstanceOf[AnyRef] != null && putBack < 0) {
    	targmap * gmats(0);
    } else {
    	gmats(1);
    }
    layers(layers.length-1).target = targ;
    if (mask.asInstanceOf[AnyRef] != null) {
    	modelmats(0) ~ modelmats(0) ∘ mask;
    }
    var i = 1;
    while (i < layers.length) {
      layers(i).forward;
      i += 1;
    }
    if (putBack >= 0) {targ <-- layers(layers.length-1).data}
    layers(layers.length-1).score
  }
  
  class Layer {
    var data:Mat = null;
    var target:Mat = null;
    var deriv:Mat = null;
    var input:Layer = null;
    var inputs:Array[Layer] = null;
    def forward = {};
    def backward:Unit = {};
    def backward(ipass:Int, pos:Long):Unit = backward;
    def score:FMat = zeros(1,1);
  }
  
  /**
   * Full connected layer. 
   * Includes a model matrix that contains the linear map. 
   */
  
  class FCLayer(val imodel:Int, val constFeat:Boolean, val aopts:ADAGrad.Opts) extends Layer {
  	var vexp:Mat = null;
    var texp:Mat = null;
    var lrate:Mat = null;
    var sumsq:Mat = null;
    var firststep = -1f;
    var waitsteps = 0;
    var epsilon = 0f;
    
    override def forward = {
    	val mm = if (constFeat) {
    		modelmats(imodel).colslice(1, modelmats(imodel).ncols);
    	} else {
    		modelmats(imodel);
    	}
    	data = mm * input.data;
    	if (constFeat) data ~ data + modelmats(imodel).colslice(0, 1);
    }
    
    override def backward(ipass:Int, pos:Long) = {
      if (imodel > 0) {
      	val mm = if (constFeat) {
      		modelmats(imodel).colslice(1, modelmats(imodel).ncols);
      	} else {
      		modelmats(imodel);
      	}
      	input.deriv = mm ^* deriv;
      }
      if (aopts != null) {
        if (firststep <= 0) firststep = pos.toFloat;
        val istep = (pos + firststep)/firststep;
      	ADAGrad.multUpdate(deriv, input.data, modelmats(imodel), sumsq, mask, lrate, texp, vexp, epsilon, istep, waitsteps);
      } else {
      	val dprod = deriv *^ input.data;
      	updatemats(imodel) = if (constFeat) (sum(deriv,2) \ dprod) else dprod;
      }
    }
    
    def initADAGrad {
      val mm = modelmats(imodel); 
      val d = mm.nrows;
      val m = mm.ncols;
    	firststep = -1f;
    	lrate = convertMat(aopts.lrate);
    	texp = convertMat(aopts.texp);
    	vexp = convertMat(aopts.vexp);
    	sumsq = convertMat(zeros(d, m));
    	sumsq.set(aopts.initsumsq);
    	waitsteps = aopts.waitsteps;
    	epsilon = aopts.epsilon;
    }
  }
  
  /**
   * Rectifying Linear Unit layer.
   */
  
  class ReLULayer extends Layer {
    override def forward = {
      data = max(input.data, 0f)
    }
    
    override def backward = {
      input.deriv = deriv ∘ (input.data > 0f);
    }
  }
  
  /**
   * Input layer is currently just a placeholder.
   */
  
  class InputLayer extends Layer {
  }
  
  /**
   * GLMLayer implements linear, logistic and hinge-loss SVM. 
   * Commonly used as an output layer so includes a score method.
   */
  
  class GLMLayer(val links:IMat) extends Layer {
    val ilinks = if (useGPU) GIMat(links) else links;
    var totflops = 0L;
    for (i <- 0 until links.length) {
      totflops += GLM.linkArray(links(i)).fnflops
    }
    
    override def forward = {
      data = GLM.preds(input.data, ilinks, totflops)
    }
    
    override def backward = {
      input.deriv = GLM.derivs(data, target, ilinks, totflops)
      if (deriv.asInstanceOf[AnyRef] != null) {
        input.deriv ~ input.deriv ∘ deriv;
      }
    }
    
    override def score:FMat = { 
      val v = GLM.llfun(data, target, ilinks, totflops);
      FMat(mean(v, 2));
    }
  }
  
  /**
   * Normalization layer adds a downward-propagating derivative term whenever its norm 
   * is different from the specified value (targetNorm).
   */
    
  class NormLayer(val targetNorm:Float, val weight:Float) extends Layer {
    var sconst:Mat = null
    
    override def forward = {
      data = input.data;  
    }
    
    override def backward = {
    	if (sconst.asInstanceOf[AnyRef] == null) sconst = data.zeros(1,1);
      sconst.set(math.min(0.1f, math.max(-0.1f, (targetNorm - norm(data)/data.length).toFloat * weight)));
      input.deriv = data ∘ sconst;
      input.deriv ~ input.deriv + deriv;
    }
  }
  
  /**
   * Dropout layer with fraction to keep "frac". Deletes the same neurons in forward and backward pass. 
   * Assumes that "randmat" is not changed between forward and backward passes. 
   */
  
  class DropoutLayer(val frac:Float) extends Layer {  
    var randmat:Mat = null;
    
    override def forward = {
      randmat = input.data + 20f;   // Hack to make a cached container to hold the random data
      if (useGPU) {
        grand(randmat.asInstanceOf[GMat]); 
      } else {
        rand(randmat.asInstanceOf[FMat]);
      }
      randmat ~ (randmat < frac)
      data = input.data ∘ randmat;
    }
    
    override def backward = {
      input.deriv = deriv ∘ randmat;
    }
  }
  
  /**
   * Computes the sum of input layers. 
   */
  
  class AddLayer extends Layer { 
    
    override def forward = {
      if (data.asInstanceOf[AnyRef] == null) data = inputs(0).data.zeros(inputs(0).data.nrows, inputs(0).data.ncols);
      data <-- inputs(0).data;
      (1 until inputs.length).map((i:Int) => data ~ data + inputs(i).data);
    }
    
    override def backward = {
      (0 until inputs.length).map((i:Int) => {
      	if (inputs(i).deriv.asInstanceOf[AnyRef] == null) inputs(i).deriv = deriv.zeros(deriv.nrows, deriv.ncols);
      	inputs(i).deriv <-- deriv
      });
    }
  }
  
  /**
   * Computes the product of its input layers. 
   */
  
  class MulLayer extends Layer {  
    
    override def forward = {
    	if (data.asInstanceOf[AnyRef] == null) data = inputs(0).data.zeros(inputs(0).data.nrows, inputs(0).data.ncols);
      data <-- inputs(0).data;
      (1 until inputs.length).map((i:Int) => data ~ data ∘ inputs(i).data);
    }

    
    override def backward = {
      val ddata = deriv ∘ data
      (0 until inputs.length).map((i:Int) => {
      	if (inputs(i).deriv.asInstanceOf[AnyRef] == null) inputs(i).deriv = deriv.zeros(deriv.nrows, deriv.ncols);
      	inputs(i).deriv <-- ddata / inputs(i).data;
      });
    }
  }
  
  /**
   * Softmax layer. Output = exp(input) / sum(exp(input))
   */
  
  class SoftmaxLayer extends Layer {    
    
    override def forward = {
      val exps = exp(input.data);
      data = exps / sum(exps);
    }
    
    override def backward = {
      val exps = exp(input.data);
      val sumexps = sum(exps);
      val isum = 1f / (sumexps ∘ sumexps);
      input.deriv = ((exps / sumexps) ∘ deriv) - (exps ∘ (isum ∘ (exps ∙ deriv))) ;
    }
  }
  
  /**
   * Tanh layer. 
   */
  
  class TanhLayer extends Layer {    
    
    override def forward = {
      data = tanh(input.data);
    }
    
    override def backward = {
      val tmp = tanh(input.data);
      input.deriv = (1 - tmp ∘ tmp) ∘ deriv;
    }
  }
  
  /**
   * Sigmoid layer. Uses GLM implementations of logistic functions for performance. 
   */
  
  class SigmoidLayer extends Layer {
    var ilinks:Mat = null;
    var totflops = 0L;
    
    override def forward = {
      if (ilinks.asInstanceOf[AnyRef] == null) {
        ilinks = izeros(input.data.nrows, 1)
        ilinks.set(GLM.logistic);
      }
      if (totflops == 0L) totflops = input.data.nrows * GLM.linkArray(1).fnflops
      if (useGPU) ilinks = GIMat(ilinks);
      data = GLM.preds(input.data, ilinks, totflops)
    }
    
    override def backward = {
      input.deriv = data - (data ∘ data);
      input.deriv ~ input.deriv ∘ deriv;
    }
  }
  
  /**
   * Softplus layer.  
   */
  
  class SoftplusLayer extends Layer {
  	var ilinks:Mat = null;
    var totflops = 0L;
   
    override def forward = {
      val big = input.data > 60f;      
      data = (1 - big) ∘ ln(1f + exp(min(60f, input.data))) + big ∘ input.data;
    }
    
    override def backward = {
      if (ilinks.asInstanceOf[AnyRef] == null) {
        ilinks = izeros(input.data.nrows, 1)
        ilinks.set(GLM.logistic);
      }
      if (totflops == 0L) totflops = input.data.nrows * GLM.linkArray(1).fnflops
      if (useGPU) ilinks = GIMat(ilinks);
      input.deriv = GLM.preds(input.data, ilinks, totflops)
      input.deriv ~ input.deriv ∘ deriv;
    }
  }
  
  /**
   * Cut layer. Need to insert these in cyclic networks so that caching works. 
   */
  
  class CutLayer extends Layer {
    
    override def forward = {
      if (data.asInstanceOf[AnyRef] == null) {
        data = input.data.zeros(input.data.nrows, input.data.ncols);
        input.deriv = input.data.zeros(input.data.nrows, input.data.ncols);
      }
      data <-- input.data;
    }
    
    override def backward = {
      input.deriv <-- deriv;      
    }
  }
  
  /**
   * Natural Log layer. 
   */
  
  class LnLayer extends Layer {
    
    override def forward = {
      data = ln(input.data);
    }
    
    override def backward = {
      input.deriv = deriv/input.data;    
    }
  }
  
  /**
   * Exponential layer. 
   */
  
  class ExpLayer extends Layer {
    
    override def forward = {
      data = exp(input.data);
    }
    
    override def backward = {
      input.deriv = deriv ∘ data;    
    }
  }
  
  /**
   * Sum layer. 
   */
  
  class SumLayer extends Layer {
    var vmap:Mat = null;
    
    override def forward = {
      data = sum(input.data);
    }
    
    override def backward = {
      if (vmap.asInstanceOf[AnyRef] == null) vmap = deriv.ones(data.nrows, 1);
      input.deriv = vmap * deriv;    
    }
  }
  /**
   * Creates a copy of the input grown by a small piece of the last minibatch to support lagged updates
   * e.g. for word2vec
   */
  
  class LagLayer(siz:Int) extends Layer {
    var lastBatch:Mat = null;
    
    override def forward = {
      if (lastBatch.asInstanceOf[AnyRef] == null) {
        data = input.data.zeros(input.data.nrows, siz, 0) \ input.data;
      } else {
        data = data.colslice(input.data.ncols, input.data.ncols+siz, data);
        data = input.data.colslice(0, input.data.ncols, data, siz);
      }      
    }
  }
  
  /**
   * Block matrix-matrix multiply. Each output block is nr x nc. nc needs to be a submultiple of the minibatch size. 
   * Each element of the block moves by step in the corresponding matrix. 
   */
  
  class blockGemmLayer(nr:Int, nc:Int, step:Int, reps:Int, inshift:Int) extends Layer {
  	val aspect = nr / nc;
  	val astep = if (step == 1) nc else 1;
  	val shift0 = if (inshift >= 0) inshift else 0;
  	val shift1 = if (inshift < 0) - inshift else 0;
  	
    override def forward = {
      val nrows = inputs(0).data.nrows;
      val nrowsa = nrows * aspect;
      if (data.asInstanceOf[AnyRef] == null) data = inputs(0).data.zeros(nr, nc * reps);
      
      inputs(0).data.blockGemm(1, 0, nr, nc, reps, 
                          shift0*nrowsa, step*nrowsa, astep*nrowsa, 
      		inputs(1).data, shift1*nrows,  step*nrows,  astep*nrows, 
      		data,           0,             step*nr,     astep*nr);      
    }
    
    override def backward = {
      val nrows = inputs(0).data.nrows;
      val nrowsa = nrows * aspect;
      if (inputs(0).deriv.asInstanceOf[AnyRef] == null) inputs(0).deriv = inputs(0).data.zeros(nrows, inputs(0).data.ncols);
      if (inputs(1).deriv.asInstanceOf[AnyRef] == null) inputs(1).deriv = inputs(1).data.zeros(nrows, inputs(1).data.ncols);
      
      inputs(1).data.blockGemm(0, 1, nrows, nc, reps, 
      		                 shift1*nrows,  step*nrows,  astep*nrows, 
      		deriv,           0,             step*nr,     astep*nr, 
      		inputs(0).deriv, shift0*nrowsa, step*nrowsa, astep*nrowsa);
      
      inputs(0).data.blockGemm(0, 0, nrows, nr, reps, 
      		                 shift0*nrowsa, step*nrowsa, astep*nrowsa, 
      		deriv,           0,             step*nr,     astep*nr, 
      		inputs(1).deriv, shift1*nrows,  step*nrows,  astep*nrows);
    
    }
  }
}

object DNN  {
  trait Opts extends Model.Opts {
	  var layers:Seq[LayerSpec] = null;
    var links:IMat = null;
    var nweight:Float = 0.1f;
    var dropout:Float = 0.5f;
    var targetNorm:Float = 1f;
    var targmap:Mat = null;
    var dmask:Mat = null;
    var constFeat:Boolean = false;
    var aopts:ADAGrad.Opts = null;
  }
  
  class Options extends Opts {}
  
  class LayerSpec(val input:LayerSpec, val inputs:Array[LayerSpec]) {
    var myLayer:DNN#Layer = null;
  }
  
  class ModelLayerSpec(input:LayerSpec) extends LayerSpec(input, null){}
  
  class FC(input:LayerSpec, val outsize:Int, val constFeat:Boolean, val aopts:ADAGrad.Opts) extends ModelLayerSpec(input) {}
  
  class ReLU(input:LayerSpec) extends LayerSpec(input, null) {}
  
  class Input extends LayerSpec(null, null) {}
  
  class GLM(input:LayerSpec, val links:IMat) extends LayerSpec(input, null) {}
  
  class Norm(input:LayerSpec, val targetNorm:Float, val weight:Float) extends LayerSpec(input, null) {}
  
  class Dropout(input:LayerSpec, val frac:Float) extends LayerSpec(input, null) {}
  
  class Add(inputs:Array[LayerSpec]) extends LayerSpec(null, inputs) {}
  
  class Mul(inputs:Array[LayerSpec]) extends LayerSpec(null, inputs) {}
  
  class Softmax(input:LayerSpec) extends LayerSpec(input, null) {}
  
  class Tanh(input:LayerSpec) extends LayerSpec(input, null) {}
  
  class Sigmoid(input:LayerSpec) extends LayerSpec(input, null) {}
  
  class Softplus(input:LayerSpec) extends LayerSpec(input, null) {}
  
  class Cut(input:LayerSpec) extends LayerSpec(input, null) {}
  
  class Ln(input:LayerSpec) extends LayerSpec(input, null) {}
  
  class Exp(input:LayerSpec) extends LayerSpec(input, null) {}
  
  class Sum(input:LayerSpec) extends LayerSpec(input, null) {}
  
  class Lag(input:LayerSpec, val siz:Int) extends LayerSpec(input, null) {}
  
  /**
   * Build a stack of layer specs. layer(0) is an input layer, layer(n-1) is a GLM layer. 
   * Intermediate layers are FC alternating with ReLU, starting and ending with FC. 
   * First FC layer width is given as an argument, then it tapers off by taper.
   */
  
  def dlayers(depth0:Int, width:Int, taper:Float, ntargs:Int, opts:Opts, nonlin:Int = 1):Array[LayerSpec] = {
    val depth = (depth0/2)*2 + 1;              // Round up to an odd number of layers 
    val layers = new Array[LayerSpec](depth);
    var w = width;
    layers(0) = new Input;
    for (i <- 1 until depth - 2) {
    	if (i % 2 == 1) {
    		layers(i) = new FC(layers(i-1), w, opts.constFeat, opts.aopts);
    		w = (taper*w).toInt;
    	} else {
    	  nonlin match {
    	    case 1 => layers(i) = new Tanh(layers(i-1));
    	    case 2 => layers(i) = new Sigmoid(layers(i-1));
    	    case 3 => layers(i) = new ReLU(layers(i-1));
    	    case 4 => layers(i) = new Softplus(layers(i-1));
    	  }
    	}
    }
    layers(depth-2) = new FC(layers(depth-3), ntargs, opts.constFeat, opts.aopts);
    layers(depth-1) = new GLM(layers(depth-2), opts.links);
    opts.layers = layers
    layers
  }
  
  /**
   * Build a stack of layer specs. layer(0) is an input layer, layer(n-1) is a GLM layer. 
   * Intermediate layers are FC, ReLU, Norm, starting and ending with FC. 
   * First FC layer width is given as an argument, then it tapers off by taper.
   */
  
  def dlayers3(depth0:Int, width:Int, taper:Float, ntargs:Int, opts:Opts, nonlin:Int = 1):Array[LayerSpec] = {
    val depth = (depth0/3)*3;              // Round up to a multiple of 3 
    val layers = new Array[LayerSpec](depth);
    var w = width;
    layers(0) = new Input;
    for (i <- 1 until depth - 2) {
    	if (i % 3 == 1) {
    		layers(i) = new FC(layers(i-1), w, opts.constFeat, opts.aopts);
    		w = (taper*w).toInt;
    	} else if (i % 3 == 2) {
    	  nonlin match {
    	    case 1 => layers(i) = new Tanh(layers(i-1));
    	    case 2 => layers(i) = new Sigmoid(layers(i-1));
    	    case 3 => layers(i) = new ReLU(layers(i-1));
    	    case 4 => layers(i) = new Softplus(layers(i-1));
    	  }
    	} else {
    	  layers(i) = new Norm(layers(i-1), opts.targetNorm, opts.nweight);
    	}
    }
    layers(depth-2) = new FC(layers(depth-3), ntargs, opts.constFeat, opts.aopts);
    layers(depth-1) = new GLM(layers(depth-2), opts.links);
    opts.layers = layers
    layers
  }
  
  /**
   * Build a stack of layer specs. layer(0) is an input layer, layer(n-1) is a GLM layer. 
   * Intermediate layers are FC, ReLU, Norm, Dropout, starting and ending with FC. 
   * First FC layer width is given as an argument, then it tapers off by taper.
   */
  
  def dlayers4(depth0:Int, width:Int, taper:Float, ntargs:Int, opts:Opts, nonlin:Int = 1):Array[LayerSpec] = {
    val depth = ((depth0+1)/4)*4 - 1;              // Round up to a multiple of 4 - 1
    val layers = new Array[LayerSpec](depth);
    var w = width;
    layers(0) = new Input;
    for (i <- 1 until depth - 2) {
      (i % 4) match {
        case 1 => {
        	layers(i) = new FC(layers(i-1), w, opts.constFeat, opts.aopts);
        	w = (taper*w).toInt;
        }
        case 2 => {
        	nonlin match {
        	case 1 => layers(i) = new Tanh(layers(i-1));
        	case 2 => layers(i) = new Sigmoid(layers(i-1));
        	case 3 => layers(i) = new ReLU(layers(i-1));
        	case 4 => layers(i) = new Softplus(layers(i-1));
        	}
        }
        case 3 => {
          layers(i) = new Norm(layers(i-1), opts.targetNorm, opts.nweight);
        }
        case _ => {
          layers(i) = new Dropout(layers(i-1), opts.dropout);          
        }
      }
    }
    layers(depth-2) = new FC(layers(depth-3), ntargs, opts.constFeat, opts.aopts);
    layers(depth-1) = new GLM(layers(depth-2), opts.links);
    opts.layers = layers
    layers
  }
  
  def mkDNNModel(fopts:Model.Opts) = {
    new DNN(fopts.asInstanceOf[DNN.Opts])
  }
  
  def mkUpdater(nopts:Updater.Opts) = {
    new ADAGrad(nopts.asInstanceOf[ADAGrad.Opts])
  } 
  
  def mkRegularizer(nopts:Mixin.Opts):Array[Mixin] = {
    Array(new L1Regularizer(nopts.asInstanceOf[L1Regularizer.Opts]))
  }
    
  class LearnOptions extends Learner.Options with DNN.Opts with MatDS.Opts with ADAGrad.Opts with L1Regularizer.Opts

  def learner(mat0:Mat, targ:Mat) = {
    val opts = new LearnOptions
    if (opts.links == null) opts.links = izeros(1,targ.nrows)
    opts.links.set(1)
    opts.batchSize = math.min(100000, mat0.ncols/30 + 1)
    dlayers(3, 0, 1f, targ.nrows, opts)                   // default to a 3-layer network
  	val nn = new Learner(
  	    new MatDS(Array(mat0, targ), opts), 
  	    new DNN(opts), 
  	    Array(new L1Regularizer(opts)),
  	    new ADAGrad(opts), 
  	    opts)
    (nn, opts)
  }
  
  def learnerX(mat0:Mat, targ:Mat) = {
    val opts = new LearnOptions
    if (opts.links == null) opts.links = izeros(1,targ.nrows)
    opts.links.set(1)
    opts.batchSize = math.min(100000, mat0.ncols/30 + 1)
    dlayers(3, 0, 1f, targ.nrows, opts)                   // default to a 3-layer network
  	val nn = new Learner(
  	    new MatDS(Array(mat0, targ), opts), 
  	    new DNN(opts), 
  	    null,
  	    null, 
  	    opts)
    (nn, opts)
  }
  
  class FDSopts extends Learner.Options with DNN.Opts with FilesDS.Opts with ADAGrad.Opts with L1Regularizer.Opts
  
  def learner(fn1:String, fn2:String):(Learner, FDSopts) = learner(List(FilesDS.simpleEnum(fn1,1,0),
  		                                                                  FilesDS.simpleEnum(fn2,1,0)));
  
  def learner(fn1:String):(Learner, FDSopts) = learner(List(FilesDS.simpleEnum(fn1,1,0)));

  def learner(fnames:List[(Int)=>String]):(Learner, FDSopts) = {   
    val opts = new FDSopts
    opts.fnames = fnames
    opts.batchSize = 100000;
    opts.eltsPerSample = 500;
    implicit val threads = threadPool(4);
    val ds = new FilesDS(opts)
//    dlayers(3, 0, 1f, targ.nrows, opts)                   // default to a 3-layer network
  	val nn = new Learner(
  			ds, 
  	    new DNN(opts), 
  	    Array(new L1Regularizer(opts)),
  	    new ADAGrad(opts), 
  	    opts)
    (nn, opts)
  } 
  
  def learnerX(fn1:String, fn2:String):(Learner, FDSopts) = learnerX(List(FilesDS.simpleEnum(fn1,1,0),
  		                                                                  FilesDS.simpleEnum(fn2,1,0)));
  
  def learnerX(fn1:String):(Learner, FDSopts) = learnerX(List(FilesDS.simpleEnum(fn1,1,0)));
  
  def learnerX(fnames:List[(Int)=>String]):(Learner, FDSopts) = {   
    val opts = new FDSopts
    opts.fnames = fnames
    opts.batchSize = 100000;
    opts.eltsPerSample = 500;
    implicit val threads = threadPool(4);
    val ds = new FilesDS(opts)
//    dlayers(3, 0, 1f, targ.nrows, opts)                   // default to a 3-layer network
  	val nn = new Learner(
  			ds, 
  	    new DNN(opts), 
  	    null,
  	    null, 
  	    opts)
    (nn, opts)
  }
    // This function constructs a learner and a predictor. 
  def learner(mat0:Mat, targ:Mat, mat1:Mat, preds:Mat):(Learner, LearnOptions, Learner, LearnOptions) = {
    val mopts = new LearnOptions;
    val nopts = new LearnOptions;
    mopts.lrate = 1f
    mopts.batchSize = math.min(10000, mat0.ncols/30 + 1)
    mopts.autoReset = false
    if (mopts.links == null) mopts.links = izeros(targ.nrows,1)
    nopts.links = mopts.links
    nopts.batchSize = mopts.batchSize
    nopts.putBack = 1
    dlayers(3, 0, 1f, targ.nrows, mopts)                   // default to a 3-layer network
    val model = new DNN(mopts)
    val mm = new Learner(
        new MatDS(Array(mat0, targ), mopts), 
        model, 
        Array(new L1Regularizer(mopts)),
        new ADAGrad(mopts), mopts)
    val nn = new Learner(
        new MatDS(Array(mat1, preds), nopts), 
        model, 
        null,
        null, 
        nopts)
    (mm, mopts, nn, nopts)
  }
  
  def predictor(model0:Model, mat0:Mat, preds:Mat):(Learner, LearnOptions) = {
    val model = model0.asInstanceOf[DNN];
    val opts = new LearnOptions;
    opts.batchSize = math.min(10000, mat0.ncols/30 + 1)
    opts.links = model.opts.links;
    opts.layers = model.opts.layers;
    opts.addConstFeat = model.opts.asInstanceOf[DataSource.Opts].addConstFeat;
    opts.putBack = 1;
    opts.dropout = 1f;
    
    val newmod = new DNN(opts);
    newmod.refresh = false;
    newmod.copyFrom(model)
    val nn = new Learner(
        new MatDS(Array(mat0, preds), opts), 
        newmod, 
        null,
        null, 
        opts);
    (nn, opts)
  }
  
  class LearnParOptions extends ParLearner.Options with DNN.Opts with FilesDS.Opts with ADAGrad.Opts with L1Regularizer.Opts;
  
  def learnPar(fn1:String, fn2:String):(ParLearnerF, LearnParOptions) = {learnPar(List(FilesDS.simpleEnum(fn1,1,0), FilesDS.simpleEnum(fn2,1,0)))}
  
  def learnPar(fnames:List[(Int) => String]):(ParLearnerF, LearnParOptions) = {
    val opts = new LearnParOptions;
    opts.batchSize = 10000;
    opts.lrate = 1f;
    opts.fnames = fnames;
    implicit val threads = threadPool(4)
    val nn = new ParLearnerF(
        new FilesDS(opts), 
        opts, mkDNNModel _,
        opts, mkRegularizer _,
        opts, mkUpdater _, 
        opts)
    (nn, opts)
  }
}


